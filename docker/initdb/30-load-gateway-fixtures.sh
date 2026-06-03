#!/usr/bin/env bash
set -euo pipefail

escape_sed_replacement() {
  printf '%s' "$1" | sed 's/[&]/\\&/g'
}

SUFFIX="${GATEWAY_SEED_SUFFIX:-01}"
MQTT_PUBLISH_BASE_URI="${GATEWAY_MQTT_PUBLISH_BASE_URI:-paho-mqtt5:?brokerUrl=tcp://host.docker.internal:1883&userName=artemis&password=artemis}"
MQTT_SUBSCRIBE_BASE_URI="${GATEWAY_MQTT_SUBSCRIBE_BASE_URI:-paho-mqtt5:?brokerUrl=tcp://host.docker.internal:1883&userName=artemis&password=artemis}"
OPCUA_TRANSPORT_CONFIG="${GATEWAY_OPCUA_TRANSPORT_CONFIG:-host.docker.internal:12686/milo}"
OPCUA_FEEDBACK_NODE="${GATEWAY_OPCUA_FEEDBACK_NODE:-ns=2;s=virtual_plc_feedback}"
OPCUA_FAULT_NODE="${GATEWAY_OPCUA_FAULT_NODE:-ns=2;s=virtual_plc_fault}"
OPCUA_REFERENCE_WRITE_NODE="${GATEWAY_OPCUA_REFERENCE_WRITE_NODE:-ns=2;s=virtual_plc_reference_write}"

export PGPASSWORD="${POSTGRES_PASSWORD}"

sed \
  -e "s/__SUFFIX__/$(escape_sed_replacement "${SUFFIX}")/g" \
  -e "s#__MQTT_PUBLISH_BASE_URI__#$(escape_sed_replacement "${MQTT_PUBLISH_BASE_URI}")#g" \
  -e "s#__MQTT_SUBSCRIBE_BASE_URI__#$(escape_sed_replacement "${MQTT_SUBSCRIBE_BASE_URI}")#g" \
  /docker-entrypoint-initdb.d/10-device-gateway-seed.sql.template \
  | psql --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" --set ON_ERROR_STOP=1

sed \
  -e "s/__SUFFIX__/$(escape_sed_replacement "${SUFFIX}")/g" \
  -e "s#__OPCUA_TRANSPORT_CONFIG__#$(escape_sed_replacement "${OPCUA_TRANSPORT_CONFIG}")#g" \
  -e "s#__OPCUA_FEEDBACK_NODE__#$(escape_sed_replacement "${OPCUA_FEEDBACK_NODE}")#g" \
  -e "s#__OPCUA_FAULT_NODE__#$(escape_sed_replacement "${OPCUA_FAULT_NODE}")#g" \
  -e "s#__OPCUA_REFERENCE_WRITE_NODE__#$(escape_sed_replacement "${OPCUA_REFERENCE_WRITE_NODE}")#g" \
  /docker-entrypoint-initdb.d/20-device-gateway-opcua-seed.sql.template \
  | psql --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" --set ON_ERROR_STOP=1
