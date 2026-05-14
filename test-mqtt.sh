#!/usr/bin/env bash
# =============================================================================
# test-mqtt.sh — Manual MQTT integration smoke test for moqui-device-gateway
#
# Requires:
#   - mosquitto-clients
#   - psql
#
# Example:
#   ./test-mqtt.sh
#   DB_FLAVOR=yugabyte ./test-mqtt.sh
#
# Expected gateway startup:
#   mvn quarkus:dev -Dquarkus.profile=integration
# =============================================================================
set -euo pipefail

BROKER="${BROKER:-localhost}"
PORT="${PORT:-1883}"
USER_NAME="${USER_NAME:-artemis}"
PASSWORD="${PASSWORD:-artemis}"
TOPIC_IN="${TOPIC_IN:-iot/parameters/in}"
DB_FLAVOR="${DB_FLAVOR:-postgres}"
DB_HOST="${DB_HOST:-localhost}"
DB_NAME="${DB_NAME:-moqui}"
DB_USER="${DB_USER:-moqui}"
DB_PASSWORD="${DB_PASSWORD:-moqui}"
GATEWAY_HEALTH_URL="${GATEWAY_HEALTH_URL:-http://localhost:8081/q/health/live}"

case "$DB_FLAVOR" in
    postgres) DB_PORT="${DB_PORT:-5432}" ;;
    yugabyte) DB_PORT="${DB_PORT:-5433}" ;;
    *) echo "Unsupported DB_FLAVOR: $DB_FLAVOR (use postgres or yugabyte)"; exit 1 ;;
esac

RUN_ID="$(date +%s)"
PARAM_PREFIX="SHELL_TEST_${RUN_ID}"

log() { printf '[test-mqtt] %s\n' "$*"; }
fail() { log "FAILED: $*"; exit 1; }

check_deps() {
    command -v mosquitto_pub >/dev/null || fail "mosquitto_pub not found. Install mosquitto-clients."
    command -v curl >/dev/null || fail "curl not found."
    command -v psql >/dev/null || fail "psql not found. Install postgresql-client."
}

publish() {
    local payload="$1"
    mosquitto_pub \
        -h "$BROKER" -p "$PORT" \
        -u "$USER_NAME" -P "$PASSWORD" \
        -t "$TOPIC_IN" \
        -m "$payload" \
        -V 5 \
        --qos 1
}

query_db() {
    PGPASSWORD="$DB_PASSWORD" \
        psql "host=$DB_HOST port=$DB_PORT user=$DB_USER dbname=$DB_NAME sslmode=disable" \
        --no-psqlrc -tAc "$1"
}

wait_for_count() {
    local sql="$1"
    local expected="$2"
    local attempts="${3:-15}"
    local delay="${4:-1}"
    local current=""

    for _ in $(seq 1 "$attempts"); do
        current="$(query_db "$sql" | tr -d '[:space:]')"
        if [ "$current" = "$expected" ]; then
            printf '%s' "$current"
            return 0
        fi
        sleep "$delay"
    done

    printf '%s' "$current"
    return 1
}

cleanup_rows() {
    query_db "DELETE FROM PARAMETER_LOG WHERE PARAMETER_ID LIKE '${PARAM_PREFIX}%'" >/dev/null
}

check_gateway_health() {
    curl -fsS "$GATEWAY_HEALTH_URL" >/dev/null || fail "Gateway health endpoint is not reachable at $GATEWAY_HEALTH_URL"
}

check_broker() {
    mosquitto_pub \
        -h "$BROKER" -p "$PORT" \
        -u "$USER_NAME" -P "$PASSWORD" \
        -t "test/smoke" -m "ping" \
        -V 5 --qos 0 >/dev/null
}

main() {
    trap cleanup_rows EXIT
    check_deps

    log "Gateway health check"
    check_gateway_health
    log "Gateway reachable on $GATEWAY_HEALTH_URL"

    log "Broker smoke test"
    check_broker
    log "Broker reachable on $BROKER:$PORT"

    log "DB smoke test ($DB_FLAVOR at $DB_HOST:$DB_PORT)"
    query_db "SELECT 1" >/dev/null
    log "DB reachable"

    log ""
    log "=== TC-01: Logging payload -> PARAMETER_LOG insert ==="
    local pid_log="${PARAM_PREFIX}_LOG"
    local payload_log="{\"parameterId\":\"${pid_log}\",\"numericValue\":42.5,\"purposeEnumId\":\"DrpLogging\"}"
    publish "$payload_log"
    if wait_for_count "SELECT COUNT(*) FROM PARAMETER_LOG WHERE PARAMETER_ID='${pid_log}'" "1" >/dev/null; then
        local row
        row="$(query_db "SELECT PARAMETER_LOG_ID, NUMERIC_VALUE FROM PARAMETER_LOG WHERE PARAMETER_ID='${pid_log}'")"
        log "TC-01 PASSED ✓ — row: $row"
    else
        fail "TC-01 expected 1 PARAMETER_LOG row for $pid_log"
    fi

    log ""
    log "=== TC-02: Auto-generated PARAMETER_LOG_ID ==="
    local pid_uuid="${PARAM_PREFIX}_UUID"
    local payload_uuid="{\"parameterId\":\"${pid_uuid}\",\"numericValue\":99.0,\"purposeEnumId\":\"DrpLogging\"}"
    publish "$payload_uuid"
    wait_for_count "SELECT COUNT(*) FROM PARAMETER_LOG WHERE PARAMETER_ID='${pid_uuid}'" "1" >/dev/null || \
        fail "TC-02 expected 1 PARAMETER_LOG row for $pid_uuid"
    local row_id
    row_id="$(query_db "SELECT PARAMETER_LOG_ID FROM PARAMETER_LOG WHERE PARAMETER_ID='${pid_uuid}'" | tr -d '[:space:]')"
    if [ -n "$row_id" ] && [ "${#row_id}" -ge 32 ]; then
        log "TC-02 PASSED ✓ — auto-generated ID: $row_id"
    else
        fail "TC-02 row not found or PARAMETER_LOG_ID too short: '$row_id'"
    fi

    log ""
    log "=== TC-03: State payload -> PARAMETER update ==="
    local param_state="DG_PARAM_STATE"
    local payload_state="{\"parameterId\":\"${param_state}\",\"symbolicValue\":\"RUN_${RUN_ID}\",\"purposeEnumId\":\"DrpControl\"}"
    publish "$payload_state"
    local symbolic_value=""
    for _ in $(seq 1 15); do
        symbolic_value="$(query_db "SELECT COALESCE(SYMBOLIC_VALUE, '') FROM PARAMETER WHERE PARAMETER_ID='${param_state}'" | tr -d '[:space:]')"
        if [ "$symbolic_value" = "RUN_${RUN_ID}" ]; then
            break
        fi
        sleep 1
    done
    if [ "$symbolic_value" = "RUN_${RUN_ID}" ]; then
        log "TC-03 PASSED ✓ — PARAMETER updated for $param_state"
    else
        fail "TC-03 expected PARAMETER.SYMBOLIC_VALUE=RUN_${RUN_ID} for $param_state, got '$symbolic_value'"
    fi

    log ""
    log "=== TC-04: Burst of 5 logging messages ==="
    local pid_burst="${PARAM_PREFIX}_BURST"
    for i in 1 2 3 4 5; do
        publish "{\"parameterId\":\"${pid_burst}\",\"numericValue\":${i}.0,\"purposeEnumId\":\"DrpLogging\"}"
    done
    if wait_for_count "SELECT COUNT(*) FROM PARAMETER_LOG WHERE PARAMETER_ID='${pid_burst}'" "5" 20 1 >/dev/null; then
        log "TC-04 PASSED ✓ — all 5 rows inserted"
    else
        fail "TC-04 expected 5 PARAMETER_LOG rows for $pid_burst"
    fi

    log ""
    log "=== TC-05: Malformed payload is discarded and gateway stays alive ==="
    mosquitto_pub \
        -h "$BROKER" -p "$PORT" \
        -u "$USER_NAME" -P "$PASSWORD" \
        -t "$TOPIC_IN" \
        -m '{"parameterId":"'${PARAM_PREFIX}_BAD'","numericValue":' \
        -V 5 \
        --qos 1
    sleep 2
    check_gateway_health
    log "TC-05 PASSED ✓ — malformed payload did not kill the gateway"

    log ""
    log "All manual MQTT smoke checks passed."
}

main "$@"
