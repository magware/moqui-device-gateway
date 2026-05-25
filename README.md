# moqui-device-gateway

Standalone Apache Camel gateway on Quarkus using the `moqui-device` device model.

The gateway handles high-level MQTT and OPC UA protocols, persists device data into the
shared `PARAMETER` / `PARAMETER_LOG` tables, and exports device configuration files to PLCs.

Stack: **Quarkus 3.34.6 · Apache Camel 4.x · Eclipse Milo 1.1.1 · Java 21**

---

## Architecture

```
moqui-device (Moqui JVM)                     moqui-device-gateway (standalone JVM)
──────────────────────────────────           ─────────────────────────────────────
run#DeviceRequest                            REST API (:8081)
  └─ run#GatewayDeviceRequest  ─── HTTP ───► GatewayRequestService
       (DeviceGatewayServices.xml)           └─ direct:* Camel routes
run#DeviceConfigExport                       direct:run-device-config-export
JDBC (shared PostgreSQL) ─────────────────── JDBC (AgroalDataSource)
```

Moqui uses `run#GatewayDeviceRequest` (`DeviceGatewayServices.xml`) as the
`run#DeviceRequestInternal` implementation for gateway-dispatched requests.
The gateway re-queries the DB autonomously using its own JDBC pool;
pre-resolved `requestItemList` / `bulkRequestItemList` are intentionally discarded.

Gateway dispatch rules:

- If `DEVICE_REQUEST.CONNECTION_NAME` is set, dispatch is driven by `DEVICE_CONNECTION.DRIVER_ENUM_ID`:
  - `DcdOpcUa` → OPC UA routes
  - anything else → rejected (fieldbus drivers belong in `moqui-plc4j`)
- If `CONNECTION_NAME` is null, the request is treated as broker-managed:
  - `DEVICE_REQUEST.BROKER_URI` is the Camel endpoint base
  - `DEVICE_REQUEST_ITEM.QUERY` (or `DEVICE_REQUEST.QUERY`) provides the topic or node-id suffix

Naming convention used throughout the codebase:

| Term | Meaning |
|---|---|
| `subscribe` / `unsubscribe` | Create or remove a per-request dynamic consumer route |
| `read` | Inbound ingest of data arriving from a subscription or static listener |
| `write` | Outbound: push a value to the target device (MQTT publish or OPC UA node write) |

---

## Moqui-side integration (moqui-device)

### Data model conventions

Each gateway process is represented as a `Device` in Moqui:

| Field | Example value | Notes |
|---|---|---|
| `Device.deviceId` | `moqui-device-gateway1` | One per gateway process |
| `Device.deviceName` | `Moqui Device Gateway 1` | Human label |

**Two-level DeviceRequest separation** — Moqui-side routing records are distinct from gateway-side fieldbus records:

| | Moqui-side (routing) | Gateway-side (fieldbus) |
|---|---|---|
| `deviceId` | `moqui-device-gateway1` | `virtual-plc-1` |
| `routerEnumId` | `DrrMoquiDeviceGateway` | — |
| `runServiceName` | `moqui.device.DeviceGatewayServices.run#GatewayDeviceRequest` | — |
| `brokerUri` | Gateway REST base URL | MQTT broker / OPC UA address |
| `query` | Gateway-side `requestName` (e.g. `VPL_MQTT_PUBLISH_REQ`) | MQTT topic or OPC UA path |
| `connectionName` | OPC UA `DeviceConnection` if needed; null for MQTT | OPC UA connection |
| `timeout` | HTTP timeout in seconds (default: 30) | — |

`brokerUri` on the Moqui-side record holds the gateway REST base URL, optionally with an API key:

```
http://gateway-host:8081
http://gateway-host:8081?apiKey=change-me-in-production
```

The `query` field links the Moqui-side record to the gateway-side `DeviceRequest.requestName` that the
gateway will look up in the shared DB to obtain fieldbus configuration (broker, topics, node-ids, etc.).

### Moqui services

Three services are provided in `DeviceGatewayServices.xml`:

| Service | Description |
|---|---|
| `run#GatewayDeviceRequest` | Implements `run#DeviceRequestInternal`; dispatches MQTT write/subscribe/unsubscribe and OPC UA write/subscribe/unsubscribe to the gateway via REST |
| `export#DeviceConfig` | Triggers a recipe export via `POST /api/device-config/export`; accepts `deviceRuleSetId` (required), `deviceId` (optional), and `requestName` (the Moqui-side DeviceRequest holding the gateway base URL) |
| `transfer#DeviceContent` | Reads a `DeviceContent` file via the Moqui resource system and streams it Base64-encoded to `POST /api/device-content/transfer/{gwRequestName}` for SFTP or local file transfer |

### Request type dispatch

`run#GatewayDeviceRequest` maps `requestTypeEnumId` to the correct gateway REST endpoint:

| `requestTypeEnumId` | Gateway endpoint |
|---|---|
| `DrtWrite`, `DrtConfigWrite`, `DrtRead` | `POST /api/device-request/run/{query}` |
| `DrtSubscribe`, `DrtEvent`, `DrtStateChange`, `DrtCyclic` | `POST /api/device-request/subscribe/{query}` |
| `DrtUnsubscribe` | `POST /api/device-request/unsubscribe/{query}` |

### Seed data example

```sql
-- 1. Register the gateway process as a Device
INSERT INTO DEVICE (DEVICE_ID, DEVICE_NAME) VALUES ('moqui-device-gateway1', 'Moqui Device Gateway 1');

-- 2. Moqui-side routing DeviceRequest (triggers gateway via REST)
INSERT INTO DEVICE_REQUEST (REQUEST_NAME, DEVICE_ID, ROUTER_ENUM_ID, REQUEST_TYPE_ENUM_ID,
    RUN_SERVICE_NAME, BROKER_URI, QUERY, TIMEOUT)
VALUES ('GW1_MQTT_PUBLISH',
        'moqui-device-gateway1',
        'DrrMoquiDeviceGateway',
        'DrtWrite',
        'moqui.device.DeviceGatewayServices.run#GatewayDeviceRequest',
        'http://gateway-host:8081?apiKey=change-me-in-production',
        'VPL_MQTT_PUBLISH_REQ',   -- gateway-side requestName
        30);

-- 3. Gateway-side fieldbus DeviceRequest (gateway reads this from shared DB)
INSERT INTO DEVICE_REQUEST (REQUEST_NAME, DEVICE_ID, REQUEST_TYPE_ENUM_ID, BROKER_URI, QUERY)
VALUES ('VPL_MQTT_PUBLISH_REQ', 'virtual-plc-1', 'DrtWrite',
        'paho-mqtt5:?brokerUrl=tcp://broker:1883&clientId=gw-out', 'virtual_plc_reference');
```

`run#DeviceRequest` on the Moqui side applies the `onlyChangedParameters` guard before calling
`run#GatewayDeviceRequest` — the gateway is never called when no parameter has changed.

---

## Use cases

### 1. MQTT inbound — device data → gateway → database

A device publishes JSON to an MQTT topic. The gateway consumes it, normalises the payload,
and writes it into `PARAMETER_LOG` (purpose `DrpLogging`) or updates `PARAMETER` (any other purpose).

**Step 1 — configure the static listener** (or use a dynamic subscription, see use case 3):

```properties
# application.properties
mqtt.read.route.autoStartup=true
mqtt.read.consume.uri=paho-mqtt5:iot/parameters/in?brokerUrl=tcp://broker:1883&clientId=gw-in&userName=user&password=secret
```

**Step 2 — publish from the device**:

```json
{"parameterId":"PUMP_01_FEEDBACK","numericValue":87.3,"purposeEnumId":"DrpLogging"}
```

The gateway inserts a row into `PARAMETER_LOG`.

For a state-update payload (purpose not `DrpLogging`):

```json
{"parameterId":"PUMP_01_FAULT","symbolicValue":"N","purposeEnumId":"DrpControl"}
```

The gateway runs `UPDATE PARAMETER SET SYMBOLIC_VALUE = 'N' WHERE PARAMETER_ID = 'PUMP_01_FAULT'`.

**Batch payloads** (JSON array) are accepted in a single message:

```json
[
  {"parameterId":"PUMP_01_FEEDBACK","numericValue":87.3,"purposeEnumId":"DrpLogging"},
  {"parameterId":"PUMP_01_FAULT","symbolicValue":"N","purposeEnumId":"DrpLogging"}
]
```

**Scalar payloads** (a plain number or string, not JSON) are also accepted when the subscription
route was registered for a single `DEVICE_REQUEST_ITEM`. The gateway looks up `parameterId` from
the request item and maps the value to `numericValue` (if `Number`) or `symbolicValue` (otherwise).

---

### 2. MQTT outbound write — database → gateway → device

Reads the current values from `PARAMETER` for all items of a `DrtWrite` `DEVICE_REQUEST`
and publishes each value as a JSON message to the configured MQTT topic.

**Trigger via REST**:

```bash
curl -X POST http://localhost:8081/api/device-request/run/VPL_MQTT_PUBLISH_REQ \
     -H 'X-API-Key: change-me-in-production'
```

**What happens**:

1. `GatewayRequestService.runMqttWrite()` loads the `DEVICE_REQUEST` and its items from DB.
2. For each `DEVICE_REQUEST_ITEM`, it resolves the topic from `ITEM.QUERY` (or `REQUEST.QUERY`),
   builds the Camel endpoint URI from `DEVICE_REQUEST.BROKER_URI + topic`, and publishes:
   ```json
   {"parameterId":"VPL_PARAM_REFERENCE","numericValue":300.0}
   ```
3. After all items are published, fires the optional `mqtt.write.afterPublish.uri` callback
   (fire-and-forget via SEDA).

**Example response**:

```json
{
  "status": "completed",
  "routeId": "mqtt-write-device-request",
  "rowCount": 2,
  "publishUriList": [
    "paho-mqtt5:virtual_plc_reference?brokerUrl=...",
    "paho-mqtt5:virtual_plc_maincontrolword?brokerUrl=..."
  ]
}
```

---

### 3. MQTT subscribe — register a live subscription

Creates one dynamic Camel consumer route per unique topic derived from the `DEVICE_REQUEST`
items. Once started, the route consumes all messages arriving on the topic and persists them
via the standard inbound path (see use case 1).

**Trigger via REST**:

```bash
curl -X POST http://localhost:8081/api/device-request/subscribe/VPL_MQTT_SUB_REQ \
     -H 'X-API-Key: change-me-in-production'
```

The `DEVICE_REQUEST` must have `REQUEST_TYPE_ENUM_ID` in
`{DrtCyclic, DrtSubscribe, DrtEvent, DrtStateChange}`.

**What happens**:

1. For each distinct topic (resolved from `DEVICE_REQUEST_ITEM.QUERY` or `DEVICE_REQUEST.QUERY`),
   the gateway builds an endpoint URI `BROKER_URI + topic`.
2. A Camel route named `device-request-consumer-{requestName}-{index}` is added dynamically.
3. The route normalises incoming payloads and forwards them to `direct:mqtt-read-device-request`.
4. If the route for a given `routeId` already exists, it is not duplicated.

**Example response**:

```json
{
  "status": "completed",
  "routeId": "mqtt-subscribe-device-request",
  "routeIdList": ["device-request-consumer-VPL_MQTT_SUB_REQ-0", "device-request-consumer-VPL_MQTT_SUB_REQ-1"],
  "consumerUriList": ["paho-mqtt5:virtual_plc_feedback?...", "paho-mqtt5:virtual_plc_fault?..."]
}
```

**Unsubscribe** — stops and removes the routes registered for the subscription:

```bash
# DeviceRequest with REQUEST_TYPE_ENUM_ID = DrtUnsubscribe, PARENT_REQUEST_NAME = VPL_MQTT_SUB_REQ
curl -X POST http://localhost:8081/api/device-request/unsubscribe/VPL_MQTT_UNSUB_REQ \
     -H 'X-API-Key: change-me-in-production'
```

---

### 4. OPC UA subscribe — live node subscription → database

Creates one dynamic Camel Milo consumer route per OPC UA node in the `DEVICE_REQUEST` items.
Incoming `DataValue` events are normalised (unwrapped from `Variant`) and persisted into
`PARAMETER` or `PARAMETER_LOG` depending on `PURPOSE_ENUM_ID`.

**Trigger via REST**:

```bash
curl -X POST http://localhost:8081/api/device-request/subscribe/VPL_OPCUA_READ_REQ \
     -H 'X-API-Key: change-me-in-production'
```

The `DEVICE_REQUEST` must reference a `DEVICE_CONNECTION` with `DRIVER_ENUM_ID = DcdOpcUa`
and the connection must have `TRANSPORT_CONFIG` set to the OPC UA server address
(e.g. `127.0.0.1:4840/milo` or `opc.tcp://server:4840`).

Each `DEVICE_REQUEST_ITEM.QUERY` holds the OPC UA node id (e.g. `ns=2;s=virtual_plc_feedback`).

The polling interval for OPC UA subscription sampling is taken from `DEVICE_REQUEST.POLLING_INTERVAL`
(in milliseconds).

**Dynamic route URI example**:
```
milo-client:opc.tcp://127.0.0.1:4840/milo?node=RAW(ns=2;s=virtual_plc_feedback)&clientId=VPL_OPCUA_READ_REQ-0&bridgeErrorHandler=true&samplingInterval=100
```

**Unsubscribe** works the same way as for MQTT (use case 3):

```bash
curl -X POST http://localhost:8081/api/device-request/unsubscribe/VPL_OPCUA_UNSUB_REQ \
     -H 'X-API-Key: change-me-in-production'
```

---

### 5. OPC UA outbound write — database → gateway → OPC UA node

Reads current parameter values from DB and writes them to OPC UA nodes using the
Camel Milo producer. A built-in retry loop handles the initial connection establishment
latency (up to `DEVICE_REQUEST.TIMEOUT` seconds, default 5 s).

**Trigger via REST**:

```bash
curl -X POST http://localhost:8081/api/device-request/run/VPL_OPCUA_WRITE_REQ \
     -H 'X-API-Key: change-me-in-production'
```

The `DEVICE_REQUEST` must be `DrtWrite` and must reference an OPC UA `DEVICE_CONNECTION`.
Each `DEVICE_REQUEST_ITEM.QUERY` holds the target node id.

**Value resolution** (in priority order):

1. `PARAMETER.NUMERIC_VALUE` → written as `Double`
2. `PARAMETER.PARAMETER_ENUM_ID` → written as `String`
3. `PARAMETER.SYMBOLIC_VALUE` → written as `String`

**Example response**:

```json
{
  "status": "completed",
  "routeId": "opcua-write-device-request",
  "rowCount": 1,
  "publishUriList": [
    "milo-client:opc.tcp://127.0.0.1:4840/milo?node=RAW(ns=2;s=virtual_plc_reference_write)&clientId=VPL_OPCUA_WRITE_REQ-0"
  ]
}
```

---

### 6. Device config export — recipe file → PLC

Fetches parameter values bound to a `DEVICE_RULE_SET`, formats them as a Codesys-compatible
`.txt` recipe file, and transfers the file to a local path or remote SFTP endpoint.

**Trigger via REST** (multi-device — all devices in the rule set):

```bash
curl -X POST http://localhost:8081/api/device-config/export \
     -H 'X-API-Key: change-me-in-production' \
     -H 'Content-Type: application/json' \
     -d '{"deviceRuleSetId":"VPL_RULESET_1"}'
```

**Trigger via REST** (single-device — limit to one `deviceId`):

```bash
curl -X POST http://localhost:8081/api/device-config/export \
     -H 'X-API-Key: change-me-in-production' \
     -H 'Content-Type: application/json' \
     -d '{"deviceRuleSetId":"VPL_RULESET_1","deviceId":"VIRTUAL_PLC_001"}'
```

`deviceRuleSetId` is required (missing it returns HTTP 400). `deviceId` is optional: when
omitted, all devices referenced by the rule set are exported in a single multi-device recipe.

**Parameter naming convention** (priority order):

| Priority | Source | Example |
|---|---|---|
| 1 | `DEVICE_REQUEST_ITEM.REQUEST_ITEM_NAME` (when not blank) | `RecipeReference` |
| 2 | `PARAMETER.PARAMETER_ALIAS` (when not blank) | `AliasedReference` |
| 3 | `PHYSICAL_DEVICE.DEVICE_NAME.PARAMETER_DEF.PARAMETER_NAME` (always available) | `virtual_plc.RecipeReference` |

The `DeviceName.ParameterName` fallback makes the recipe safe for multi-device exports where
different `DEVICE_RULE` entries have different `deviceId` values.

**Output format** (`recipe.txt`):

```
virtual_plc.RecipeReference:=250.0
virtual_plc.RecipeState:=AUTO
```

One line per parameter: `<name>:=<value>`. Numeric values are cast to VARCHAR; enum ID
is used next if present; symbolic value is the final fallback.

**Transfer configuration**:

```properties
file.transfer.uri=sftp:admin@plc1.example.com:22/recipe
file.transfer.uri.2.enabled=true
file.transfer.uri.2=sftp:admin@plc2.example.com:22/recipe
```

**Example response**:

```json
{"status": "completed", "routeId": "run-device-config-export", "rowCount": 2}
```

---

### 7. PLC diagnostic log ingest (CODESYS LoggerFacade)

The `plc-log-consumer` route subscribes to an MQTT topic where the CODESYS
`LoggerFacade` appender publishes structured log batches. Each batch is a
numbered JSON object:

```json
{
  "1": {
    "logEventDate": "DT#2026-05-25-10:00:00",
    "loggerName": "hvac",
    "source": "tempSensor",
    "type": 1,
    "repeatCount": 1,
    "numericValue": 21.5
  },
  "2": {
    "logEventDate": "DT#2026-05-25-10:00:01",
    "loggerName": "hvac",
    "source": "",
    "type": 0,
    "repeatCount": 1,
    "message": "Standby state entered"
  }
}
```

**Routing by `source` field:**

| `source` | Storage | Key mapping |
|---|---|---|
| non-empty | `PARAMETER_LOG` | `parameterId = loggerName.source` |
| empty | `DEVICE_LOG` | `deviceId = loggerName`, `payload = full JSON entry` |

**Payload type mapping (source entries → PARAMETER_LOG):**

| `type` | PLC field | `PARAMETER_LOG` column |
|---|---|---|
| `0` | `message` | `symbolicValue` |
| `1` | `numericValue` | `numericValue` |
| `2` | `enumValue` | `symbolicValue` (as string) |

The `DT#YYYY-MM-DD-HH:MM:SS` timestamp literal is parsed and stored as
`observedDate`. When parsing fails the column is left null and
`CURRENT_TIMESTAMP` is used by the SQL `COALESCE`.

Before writing to `PARAMETER_LOG` the route ensures idempotently (ON CONFLICT DO NOTHING):
1. A shared `PlcLoggerDef` row in `PARAMETER_DEF`.
2. One `PARAMETER` row per unique `loggerName.source` combination.

**Configuration:**

```properties
# Enable in integration or production profiles
plc.log.route.autoStartup=false
plc.log.consume.uri=seda:plc-log-in   # override with real broker URI

# Integration profile override example:
# %integration.plc.log.route.autoStartup=true
# %integration.plc.log.consume.uri=paho-mqtt5:moqui-plc?brokerUrl=tcp://broker:1883&clientId=camel-plc-log-in&userName=user&password=secret
```

**Error policy:** the route never stops. The two write paths are isolated in
independent `doTry` blocks so a failure in one does not suppress the other:

- Path A failure (PARAMETER_LOG write) → batch A discarded, `recordError()` called, Path B still runs
- Path B failure (DEVICE_LOG write) → batch B discarded, `recordError()` called, Path A unaffected
- Transform failure (malformed batch) → whole batch discarded before either path runs

`inboundErrorNotifier` tracks consecutive failures and sends a REST notification to Moqui
after the configured threshold, then clears automatically on the first successful write.

---

## REST API reference

All endpoints require the gateway to be running at `http://host:8081`.

### Authentication

Two equivalent credential formats are accepted:

```
X-API-Key: <token>
Authorization: Bearer <token>
```

When `gateway.api.auth.enabled=false` (integration test profile), authentication is skipped.
Missing or invalid credentials return:

```json
HTTP 401
{"error": "unauthorized", "message": "Missing or invalid API credential."}
```

### Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/device-request/run/{requestName}` | Execute a write or subscribe request |
| `POST` | `/api/device-request/write/{requestName}` | Alias for `/run/{requestName}` (backward compat) |
| `POST` | `/api/device-request/subscribe/{requestName}` | Register a live subscription |
| `POST` | `/api/device-request/unsubscribe/{requestName}` | Remove a live subscription |
| `POST` | `/api/device-config/export` | Export a device config recipe file |
| `GET` | `/q/health` | Quarkus health (liveness + readiness) |

### Request type routing

`/run` and `/write` inspect `DEVICE_REQUEST.REQUEST_TYPE_ENUM_ID` and `CONNECTION_NAME` to dispatch:

| `REQUEST_TYPE_ENUM_ID` | Driver | Dispatched to |
|---|---|---|
| `DrtWrite`, `DrtConfigWrite` | — | `direct:mqtt-write-device-request` |
| `DrtWrite`, `DrtConfigWrite` | `DcdOpcUa` | `direct:opcua-write-device-request` |
| `DrtCyclic`, `DrtSubscribe`, `DrtEvent`, `DrtStateChange` | — | `direct:mqtt-subscribe-device-request` |
| `DrtCyclic`, `DrtSubscribe`, `DrtEvent`, `DrtStateChange` | `DcdOpcUa` | `direct:opcua-subscribe-device-request` |
| Any other | — | HTTP 400 |

`/subscribe` accepts only subscribe-type requests; `/unsubscribe` only accepts `DrtUnsubscribe`.

### Error responses

| HTTP | Condition |
|---|---|
| `400` | `requestName` not found in DB, unsupported type, missing required fields |
| `401` | Missing or invalid API key |
| `503` | Route registration failed or OPC UA write timed out after retry |
| `500` | Unexpected runtime error |

---

## Inbound payload format

The gateway accepts the following JSON shapes on any subscribed MQTT topic or OPC UA node:

### Single-row object

```json
{
  "parameterId": "PUMP_01_FEEDBACK",
  "numericValue": 87.3,
  "symbolicValue": null,
  "parameterEnumId": null,
  "observedDate": null,
  "purposeEnumId": "DrpLogging"
}
```

Only `parameterId` is strictly required when the payload comes from a device.
When `parameterId` is missing, the gateway looks it up from the matching `DEVICE_REQUEST_ITEM`.

### Array

```json
[
  {"parameterId": "P1", "numericValue": 10.0, "purposeEnumId": "DrpLogging"},
  {"parameterId": "P2", "symbolicValue": "RUN", "purposeEnumId": "DrpLogging"}
]
```

### Scalar (OPC UA `DataValue` or plain string / number)

When a subscription route is registered for a single item, the raw value (unwrapped from
`DataValue` → `Variant` → Java type) is mapped automatically:

| Java type | Mapped to |
|---|---|
| `Number` | `numericValue` |
| `Boolean` | `symbolicValue` (`"Y"` / `"N"`) |
| anything else | `symbolicValue` (`.toString()`) |

### Storage routing

| `purposeEnumId` | Table written | Operation |
|---|---|---|
| `DrpLogging` (or null/missing) | `PARAMETER_LOG` | `INSERT` with auto-generated UUID if `parameterLogId` is absent |
| Any other value | `PARAMETER` | Batch `UPDATE` of `NUMERIC_VALUE`, `SYMBOLIC_VALUE`, `PARAMETER_ENUM_ID` |

---

## Routes

| Route ID | Trigger | Description |
|---|---|---|
| `mqtt-read-device-request-consumer` | `mqtt.read.consume.uri` (broker or SEDA) | Static MQTT consumer entrypoint; auto-start controlled by `mqtt.read.route.autoStartup` |
| `mqtt-read-device-request` | `direct:mqtt-read-device-request` | Sets MQTT-specific exchange properties, delegates to `store-device-request-inbound` |
| `store-device-request-inbound` | `direct:store-device-request-inbound` | Shared inbound persistence route: routes to `PARAMETER_LOG` insert or `PARAMETER` update depending on `purposeEnumId` |
| `mqtt-write-device-request` | `direct:mqtt-write-device-request` | Delegates to `gatewayRequestService.runMqttWrite()` |
| `mqtt-subscribe-device-request` | `direct:mqtt-subscribe-device-request` | Delegates to `gatewayRequestService.subscribeMqtt()` |
| `opcua-read-device-request-consumer` | `opcua.read.consume.uri` (SEDA, disabled by default) | Static OPC UA consumer entrypoint |
| `opcua-read-device-request` | `direct:opcua-read-device-request` | Sets OPC UA-specific exchange properties, delegates to `store-device-request-inbound` |
| `opcua-write-device-request` | `direct:opcua-write-device-request` | Delegates to `gatewayRequestService.runOpcUaWrite()` |
| `opcua-subscribe-device-request` | `direct:opcua-subscribe-device-request` | Delegates to `gatewayRequestService.subscribeOpcUa()` |
| `run-device-config-export` | `direct:run-device-config-export` | SQL fetch → Codesys recipe format → `direct:transfer-file` |
| `transfer-file` | `direct:transfer-file` | File/SFTP transfer to primary endpoint, optionally to secondary |
| `transfer-device-content` | `direct:transfer-device-content` | Streams device content (G-Code, firmware, CNC programs) to a dynamic URI set by the caller |
| `plc-log-consumer` | `plc.log.consume.uri` (broker or SEDA) | PLC diagnostic log consumer; auto-start controlled by `plc.log.route.autoStartup` |
| `plc-log-ingest` | `direct:plc-log-ingest` | Transforms CODESYS LoggerFacade batch format; routes entries with `source` to `PARAMETER_LOG` (Path A) and entries without `source` to `DEVICE_LOG` (Path B); the two paths are isolated — a failure in one does not suppress the other |
| `device-request-consumer-{name}-{n}` | Dynamic (MQTT topic or OPC UA node) | Per-item subscription routes registered at runtime |

---

## Dynamic subscription lifecycle

When `subscribe` is called, the gateway creates one Camel route per unique topic or OPC UA node:

```
device-request-consumer-{REQUEST_NAME}-0
device-request-consumer-{REQUEST_NAME}-1
...
```

Each route:

1. Sets `gateway.routeId` and `gateway.requestName` as exchange properties (used by `InboundErrorNotifier`).
2. Normalises the incoming payload (JSON / scalar / `DataValue`) into a list of parameter maps.
3. Filters out empty payloads (no downstream call if normalisation yields nothing).
4. Forwards to `direct:mqtt-read-device-request` or `direct:opcua-read-device-request`.

`subscribe` is idempotent per `routeId`: if the route already exists it is not registered again.

`unsubscribe` looks up routes by prefix `device-request-consumer-{targetRequestName}-` and
stops + removes each one. Partial failures are collected and reported as a `503`; the
cleanup continues past individual failures.

---

## Configuration reference

### Server

| Property | Default | Description |
|---|---|---|
| `quarkus.http.port` | `8081` | HTTP listener port |
| `quarkus.http.host` | `0.0.0.0` | HTTP bind address |

### Security

| Property | Default | Description |
|---|---|---|
| `gateway.api.auth.enabled` | `true` | Enable REST API key authentication |
| `gateway.api.auth.header` | `X-API-Key` | Header name for the API token |
| `gateway.api.auth.token` | `change-me-in-production` | Expected token value; override with a Docker secret |

### Database

| Property | Default | Description |
|---|---|---|
| `quarkus.datasource.db-kind` | `postgresql` | JDBC driver kind |
| `quarkus.datasource.jdbc.url` | `jdbc:postgresql://localhost:5432/moqui` | JDBC URL |
| `quarkus.datasource.username` | `moqui` | DB username |
| `quarkus.datasource.password` | `moqui` | DB password |
| `quarkus.datasource.jdbc.min-size` | `2` | Minimum connection pool size |
| `quarkus.datasource.jdbc.max-size` | `10` | Maximum connection pool size |
| `quarkus.datasource.jdbc.acquisition-timeout` | `2S` | Max wait for a free connection; keep low to fail fast during DB outages |

### Gateway SQL queries

| Property | Description |
|---|---|
| `gateway.request.sql` | SQL to load `DEVICE_REQUEST` + joined `DEVICE_CONNECTION` / `ENUMERATION` rows by `REQUEST_NAME` |
| `gateway.request.items.sql` | SQL to load `DEVICE_REQUEST_ITEM` + `PARAMETER` rows; respects `ONLY_CHANGED_PARAMETERS` via `ENTITY_AUDIT_LOG` join |

### MQTT read path (device → gateway → DB)

| Property | Default | Description |
|---|---|---|
| `mqtt.read.route.autoStartup` | `true` | Start static MQTT consumer on gateway startup |
| `mqtt.read.consume.uri` | `seda:mqtt-read-device-request-in` | Consumer endpoint (replace with real broker URI in production) |
| `mqtt.read.store.log.uri` | JDBC INSERT into `PARAMETER_LOG` | Logging storage endpoint |
| `mqtt.read.store.parameter.uri` | JDBC UPDATE `PARAMETER` | Current-state storage endpoint |
| `mqtt.read.afterStore.enabled` | `true` | Fire afterStore callback after successful DB write |
| `mqtt.read.afterStore.uri` | `seda:mqtt-read-device-request-notify` | afterStore callback endpoint |

### MQTT write path (gateway → device)

| Property | Default | Description |
|---|---|---|
| `mqtt.write.sql.query` | SELECT from `DEVICE_REQUEST` + items + audit log | SQL to fetch items for a write request |
| `mqtt.write.fetch.uri` | SQL endpoint built from `mqtt.write.sql.query` | Fetch endpoint (used by the Camel XML route; the Java service bypasses this) |
| `mqtt.write.afterPublish.enabled` | `true` | Fire afterPublish callback after all items are published |
| `mqtt.write.afterPublish.uri` | `seda:mqtt-write-device-request-audit` | afterPublish callback endpoint |

### OPC UA read path (device → gateway → DB)

| Property | Default | Description |
|---|---|---|
| `opcua.read.route.autoStartup` | `false` | Start static OPC UA consumer on startup (disabled; use dynamic subscribe instead) |
| `opcua.read.consume.uri` | `seda:opcua-read-device-request-in` | Consumer endpoint |
| `opcua.read.store.log.uri` | JDBC INSERT into `PARAMETER_LOG` | Logging storage endpoint |
| `opcua.read.store.parameter.uri` | JDBC UPDATE `PARAMETER` | Current-state storage endpoint |
| `opcua.read.afterStore.enabled` | `true` | Fire afterStore callback |
| `opcua.read.afterStore.uri` | `seda:opcua-read-device-request-notify` | afterStore callback endpoint |

### OPC UA write path (gateway → device)

| Property | Default | Description |
|---|---|---|
| `opcua.write.afterPublish.enabled` | `true` | Fire afterPublish callback after all nodes are written |
| `opcua.write.afterPublish.uri` | `seda:opcua-write-device-request-audit` | afterPublish callback endpoint |

OPC UA endpoint URIs for write and subscribe are built at runtime from `DEVICE_CONNECTION.TRANSPORT_CONFIG`
(address like `127.0.0.1:4840/path`) plus each item's `QUERY` as the `node=RAW(...)` parameter.
The `DEVICE_REQUEST.TIMEOUT` column controls the write retry window (in seconds, default 5 s).
The `DEVICE_REQUEST.POLLING_INTERVAL` column sets the OPC UA `samplingInterval` for subscriptions (ms).

### PLC diagnostic log ingest

| Property | Default | Description |
|---|---|---|
| `plc.log.route.autoStartup` | `false` | Start PLC log consumer on startup |
| `plc.log.consume.uri` | `seda:plc-log-in` | MQTT endpoint for CODESYS LoggerFacade batches; override with real broker URI |
| `plc.log.store.ensure.parameter.def.uri` | SQL `INSERT INTO PARAMETER_DEF … ON CONFLICT DO NOTHING` | Idempotent upsert of the shared `PlcLoggerDef` definition row |
| `plc.log.store.ensure.parameter.uri` | SQL `INSERT INTO PARAMETER … ON CONFLICT DO NOTHING` | Idempotent upsert of one `PARAMETER` row per unique `loggerName.source` |
| `plc.log.store.device.log.uri` | SQL `INSERT INTO DEVICE_LOG …` | Storage for no-source entries (plain text log lines) |

The `%integration` and `%local` profiles set `plc.log.route.autoStartup=true` and point
`plc.log.consume.uri` to the real Artemis broker on the `moqui-plc` topic.

---

### Device config export

| Property | Default | Description |
|---|---|---|
| `device.config.export.sql.query` | SELECT recipe rows by `deviceRuleSetId` + `deviceId` | SQL to fetch parameter rows for the recipe |
| `device.config.export.fetch.uri` | SQL endpoint built from query above | Fetch endpoint |
| `device.config.export.filename` | `recipe.txt` | `CamelFileName` header value |
| `file.transfer.uri` | `seda:export-device-config-out` | Primary file / SFTP transfer endpoint |
| `file.transfer.uri.2.enabled` | `false` | Enable secondary (redundant) transfer |
| `file.transfer.uri.2` | `seda:export-device-config-out-2` | Secondary transfer endpoint |

### Subscription persistence

| Property | Default | Description |
|---|---|---|
| `gateway.subscription.registry.path` | `data/subscriptions.json` | Path (relative to working directory) where active subscription `requestName` values are persisted as JSON |

When the gateway starts, it reads this file and re-subscribes to all previously active subscriptions.
Writes are atomic (`REPLACE_EXISTING` + `ATOMIC_MOVE` via a `.tmp` swap file).

### Inbound error notification

| Property | Default | Description |
|---|---|---|
| `gateway.inbound.error.notification.enabled` | `true` | Enable persistent-error tracking |
| `gateway.inbound.error.notification.threshold.seconds` | `60` | Seconds after which a single error notification is sent to Moqui |
| `gateway.inbound.error.notification.uri` | _(empty)_ | Camel endpoint to POST the notification to (e.g. `http://moqui:8080/rest/...`); leave blank for log-only |

### Logging

| Property | Default | Description |
|---|---|---|
| `quarkus.log.level` | `INFO` | Root log level |
| `quarkus.log.console.async.enabled` | `true` | Non-blocking console logging |
| `quarkus.log.console.async.queue-length` | `4096` | Bounded console buffer |
| `quarkus.log.console.async.overflow` | `discard` | Drop excess console messages rather than stall |
| `quarkus.log.file.enabled` | `true` | Rotating file log |
| `quarkus.log.file.path` | `logs/moqui-device-gateway.log` | Log file path |
| `quarkus.log.file.async.enabled` | `true` | Non-blocking file logging |
| `quarkus.log.file.async.queue-length` | `8192` | Bounded file buffer |
| `quarkus.log.file.async.overflow` | `discard` | Drop excess file messages rather than stall |
| `quarkus.log.file.rotation.max-file-size` | `25M` | Rotate per file |
| `quarkus.log.file.rotation.max-backup-index` | `8` | Bounded log history on disk |

---

## Inbound error notification

`InboundErrorNotifier` is a CDI bean (`@Named("inboundErrorNotifier")`) called from the
`doCatch` and success paths of `store-device-request-inbound`.

Behaviour:

1. **First DB write failure** for a route: starts tracking the error, logs a `WARN`.
2. **Subsequent failures** within the threshold window: no repeat notification.
3. **After `threshold.seconds`**: sends one POST to `gateway.inbound.error.notification.uri`
   with payload:
   ```json
   {
     "eventType": "inboundError",
     "routeId": "device-request-consumer-VPL_OPCUA_READ_REQ-0",
     "requestName": "VPL_OPCUA_READ_REQ",
     "protocol": "OPC UA",
     "errorMessage": "Connection refused",
     "firstErrorTime": "2026-05-02T10:00:00Z",
     "errorDurationSeconds": 63
   }
   ```
4. **Recovery** (successful DB write): sends a recovery notification:
   ```json
   {
     "eventType": "inboundRecovered",
     "routeId": "...",
     "firstErrorTime": "...",
     "errorDurationSeconds": 120
   }
   ```

The error state is tracked per dynamic route id. The static routes (`mqtt-read-device-request`,
`opcua-read-device-request`) use the route id from `exchange.getFromRouteId()` as fallback.

Reads always continue regardless of DB failures — no inbound flow is ever blocked by storage errors.

---

## Runtime error policy

| Area | Runtime error | Data effect | Route/process effect |
|---|---|---|---|
| MQTT inbound | broker disconnect | possible gaps | route auto-reconnects (Paho reconnect config) |
| MQTT inbound | malformed payload | one payload lost | route stays alive |
| MQTT inbound | DB failure | one sample lost | route stays alive; error tracked by notifier |
| MQTT inbound | afterStore failure | none | route stays alive; warning logged |
| OPC UA inbound | endpoint/network failure | possible gaps | dynamic route survives (`bridgeErrorHandler=true`) |
| OPC UA inbound | value normalisation failure | one event lost | route stays alive; warning logged |
| OPC UA inbound | DB failure | one sample lost | route stays alive; error tracked by notifier |
| PLC log inbound | malformed batch (transform) | whole batch lost | route stays alive |
| PLC log inbound (source entries) | PARAMETER ensure failure | source batch lost | route stays alive; DEVICE_LOG path unaffected; error tracked by notifier |
| PLC log inbound (source entries) | PARAMETER_LOG write failure | source batch lost | route stays alive; DEVICE_LOG path unaffected; error tracked by notifier |
| PLC log inbound (no-source entries) | DEVICE_LOG write failure | no-source batch lost | route stays alive; PARAMETER_LOG path unaffected; error tracked by notifier |
| Outbound MQTT write | publish failure | write not completed | caller gets 503; runtime stays healthy |
| Outbound OPC UA write | write failure / timeout | write not completed | caller gets 503; runtime stays healthy |
| Dynamic subscribe | route registration failure | subscription not active | caller gets 503; other routes unaffected |
| Dynamic unsubscribe | stop/remove failure on one route | partial cleanup | cleanup continues; failures reported in response |
| Recipe export | DB / SFTP failure | export not completed | caller gets 503; runtime stays healthy |
| REST auth | missing/invalid credential | none | `401` returned |

Long retry loops are intentionally avoided on live inbound DB writes; data freshness and
route continuity are preferred over blocking waits for storage recovery.

If future requirements change from best-effort live data to lossless ingestion, introduce
an explicit persistent buffer between protocol ingress and DB persistence rather than relying
on transport semantics.

---

## Official use cases — seed data reference

The integration test seeds define four `Virtual PLC` scenarios. The naming convention is:

- MQTT topic: `{deviceName}_{parameterName}`
- OPC UA node id: `ns={idx};s={deviceName}_{parameterName}`

| Use case | `REQUEST_TYPE_ENUM_ID` | Transport | Seed request name | Address examples |
|---|---|---|---|---|
| MQTT publish | `DrtWrite` | MQTT | `VPL_MQTT_PUBLISH_REQ_*` | `virtual_plc_reference`, `virtual_plc_maincontrolword` |
| MQTT subscribe | `DrtCyclic` | MQTT | `VPL_MQTT_SUB_REQ_*` | `virtual_plc_feedback`, `virtual_plc_fault` |
| OPC UA subscribe | `DrtCyclic` | OPC UA | `VPL_OPCUA_READ_REQ_*` | `ns=2;s=virtual_plc_feedback`, `ns=2;s=virtual_plc_fault` |
| OPC UA write | `DrtWrite` | OPC UA | `VPL_OPCUA_WRITE_REQ_*` | `ns=2;s=virtual_plc_reference_write` |

---

## Production configuration examples

### Moqui-side gateway dispatch

Seed data in Moqui for triggering a gateway subscription:

```properties
# Moqui DeviceRequest that routes to the gateway process:
#   DEVICE_ID            = moqui-device-gateway1
#   ROUTER_ENUM_ID       = DrrMoquiDeviceGateway
#   RUN_SERVICE_NAME     = moqui.device.DeviceGatewayServices.run#GatewayDeviceRequest
#   BROKER_URI           = http://gateway-host:8081?apiKey=change-me-in-production
#   REQUEST_TYPE_ENUM_ID = DrtSubscribe
#   QUERY                = VPL_MQTT_SUB_REQ   (gateway-side requestName)
```

### Artemis MQTT

```properties
mqtt.read.consume.uri=paho-mqtt5:iot/parameters/in?brokerUrl=tcp://broker:1883&clientId=camel-gw-in&userName=user&password=secret
mqtt.read.afterStore.uri=seda:mqtt-read-device-request-notify

# For broker-managed write requests, DEVICE_REQUEST.BROKER_URI holds the endpoint base, e.g.:
# paho-mqtt5:?brokerUrl=tcp://broker:1883&clientId=camel-gw-out&userName=user&password=secret
# DEVICE_REQUEST_ITEM.QUERY holds the topic suffix, e.g.: virtual_plc_reference
```

### OPC UA with DeviceConnection

```properties
# DEVICE_CONNECTION.TRANSPORT_CONFIG = plc.example.com:4840/milo
# DEVICE_REQUEST_ITEM.QUERY = ns=2;s=VFD_01_SpeedRef
# DEVICE_REQUEST.POLLING_INTERVAL = 500
# DEVICE_REQUEST.TIMEOUT = 10  (seconds for write retry)
```

### API token — never store in application.properties

`gateway.api.auth.token` must be overridden at runtime. Do not commit the real token.

**Option A — environment variable** (simplest; works with `docker run` and Compose):

```bash
docker run ... -e GATEWAY_API_AUTH_TOKEN=mysecrettoken moqui-device-gateway:latest
```

Quarkus maps `GATEWAY_API_AUTH_TOKEN` → `gateway.api.auth.token` automatically.

**Option B — Docker secret** (recommended for Swarm / production Compose):

```yaml
# docker-compose.yml
services:
  moqui-device-gateway:
    image: moqui-device-gateway:latest
    secrets:
      - gateway_api_token
    environment:
      GATEWAY_API_AUTH_TOKEN_FILE: /run/secrets/gateway_api_token

secrets:
  gateway_api_token:
    external: true
```

> Quarkus does not natively read `_FILE` secrets; use a thin entrypoint wrapper or
> read the file and export it as an environment variable before starting the JVM:
> ```sh
> export GATEWAY_API_AUTH_TOKEN=$(cat /run/secrets/gateway_api_token)
> exec java $JAVA_OPTS_APPEND -jar $JAVA_APP_JAR
> ```
> Replace the `ENTRYPOINT` line in `Dockerfile.jvm` with a shell script that does this.

### SFTP recipe export

```properties
file.transfer.uri=sftp:admin@plc1.example.com:22/recipe
file.transfer.uri.2.enabled=true
file.transfer.uri.2=sftp:admin@plc2.example.com:22/recipe
```

### Inbound error notification

```properties
gateway.inbound.error.notification.enabled=true
gateway.inbound.error.notification.threshold.seconds=60
gateway.inbound.error.notification.uri=http://moqui-server:8080/rest/s1/org/moqui/device/notify/inboundError
```

---

## Build

```bash
cd moqui-device-gateway
mvn package -DskipTests
# JVM artifact: target/quarkus-app/quarkus-run.jar
```

> **Note on Maven warning:** if Maven prints _"The Maven extensions for the Quarkus Maven
> plugin are not enabled"_ despite `<extensions>true</extensions>` being present in `pom.xml`,
> it can be safely ignored — it is a known false positive in certain Maven versions and does
> not affect the build output. `BUILD SUCCESS` is the authoritative result.

> **Cross-platform note:** `target/` contains platform-specific native libraries (e.g. brotli4j).
> Always build on the target OS. Do not copy a `target/` directory built on Windows to a Linux host.

---

## Run

### Development mode

```bash
cd moqui-device-gateway
mvn quarkus:dev
# Gateway: http://localhost:8081
# Health:  http://localhost:8081/q/health
```

### JVM direct (integration profile, on the gateway host)

```bash
java -Dquarkus.profile=integration \
     -jar target/quarkus-app/quarkus-run.jar
```

The `%integration` profile activates live PostgreSQL, real Artemis MQTT endpoints
(`localhost:1883`), PLC log ingestion, and DEBUG logging for Camel categories.

For a non-standard DB port (e.g. the standalone DB container on port `5434`):

```bash
java -Dquarkus.profile=integration \
     -Dquarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5434/moqui \
     -jar target/quarkus-app/quarkus-run.jar
```

### Local developer machine (broker on remote host)

```bash
java -Dquarkus.profile=local \
     -jar target/quarkus-app/quarkus-run.jar
```

The `%local` profile connects to a local PostgreSQL and a remote Artemis broker at
`192.168.101.62:1883`.

---

## Standalone deployment (Docker)

The gateway ships with two Docker Compose files under `docker/`:

| File | Description |
|---|---|
| `docker/db-compose.yml` | PostgreSQL 18 with `init.sql` schema; exposes port `5434` on the host |
| `docker/gateway-compose.yml` | Gateway container; reaches the DB by container name on the shared `moqui-gateway-net` network |

Both compose files join the Docker network `moqui-gateway-net` (created by `db-compose.yml`).

**Prerequisites on the target server:** Java 21+, Maven 3.9+, Docker.

> **Database note:** `db-compose.yml` starts a bare PostgreSQL instance (no init scripts —
> same pattern as `moqui-framework/docker/postgres-compose.yml`). The schema must be applied
> once manually after first startup (step 4 below).
> In production the gateway connects directly to the main Moqui DB; Moqui's entity engine
> creates all required tables (`PARAMETER`, `PARAMETER_LOG`, `DEVICE_REQUEST`, etc.) on its own
> startup — no manual schema init is needed.

```bash
# 1. Build the JVM artifact (must be done on Linux)
cd moqui-device-gateway
mvn package -DskipTests

# 2. Build the Docker image
docker build -f src/main/docker/Dockerfile.jvm -t moqui-device-gateway:latest .

# 3. Start the DB (also creates moqui-gateway-net)
docker compose -f docker/db-compose.yml up -d

# 4. Apply the schema (standalone testing only — run once after first DB start)
#    From the server:
docker exec -i moqui-log-database psql -U moqui -d moqui < src/main/resources/db/init.sql
#    Or from a Windows machine via PowerShell:
#    Get-Content "...\src\main\resources\db\init.sql" | ssh user@host "docker exec -i moqui-log-database psql -U moqui -d moqui"

# 5. Verify schema
docker exec moqui-log-database psql -U moqui -d moqui -c "\dt"

# 6. Start the gateway
docker compose -f docker/gateway-compose.yml up -d

# 7. Follow startup logs
docker logs -f moqui-device-gateway
```

Expected startup output:

```
INFO  Route: plc-log-consumer started and consuming from: paho-mqtt5://moqui-plc?...
INFO  Route: mqtt-read-device-request-consumer started and consuming from: paho-mqtt5://iot/parameters/in?...
INFO  Camel started with N routes
```

**Updating after a source change:**

```bash
mvn package -DskipTests
docker build -f src/main/docker/Dockerfile.jvm -t moqui-device-gateway:latest .
docker compose -f docker/gateway-compose.yml up -d --force-recreate
```

**Environment variable overrides** (Quarkus MicroProfile Config convention —
dots and hyphens → underscores, all uppercase):

| Property | Env var |
|---|---|
| `quarkus.datasource.jdbc.url` | `QUARKUS_DATASOURCE_JDBC_URL` |
| `gateway.api.auth.token` | `GATEWAY_API_AUTH_TOKEN` |
| `mqtt.read.consume.uri` | `MQTT_READ_CONSUME_URI` |
| `mqtt.write.publish.uri` | `MQTT_WRITE_PUBLISH_URI` |
| `plc.log.consume.uri` | `PLC_LOG_CONSUME_URI` |
| `plc.log.route.autoStartup` | `PLC_LOG_ROUTE_AUTOSTARTUP` |

The `gateway-compose.yml` already overrides the MQTT broker URIs to use the host LAN IP
(`192.168.101.62`) so that broker connections resolve correctly from inside the container.
Adjust that IP to match your actual broker host if it differs.

---

## Testing

### Unit tests

Run without external infrastructure:

```bash
mvn test
```

Covers route loading (`GatewayRouteTest`) and REST auth (`GatewayResourceSecurityTest`).
MQTT endpoints are replaced by SEDA; OPC UA endpoints are disabled.

### Integration tests

Require live PostgreSQL on `localhost:5432` and Artemis MQTT on `localhost:1883`.
Bring up the baseline with Docker (see next section), then run explicitly:

```bash
mvn test -Dquarkus.profile=integration -Dtest=GatewaySeededRouteIntegrationTest
mvn test -Dquarkus.profile=integration -Dtest=OpcUaGatewayIntegrationTest
```

`OpcUaGatewayIntegrationTest` starts an embedded Eclipse Milo OPC UA server on a random port
— no external OPC UA infrastructure required.

`MqttInboundIntegrationTest` requires Artemis and PostgreSQL:

```bash
mvn test -Dquarkus.profile=integration -Dtest=MqttInboundIntegrationTest
```

### Manual MQTT smoke tests

```bash
./test-mqtt.sh
# Configurable via: BROKER, PORT, USER_NAME, PASSWORD, TOPIC_IN, DB_HOST, DB_FLAVOR
```

Runs TC-01 through TC-05 (logging insert, UUID auto-gen, state update, burst, malformed payload survival).

---

### Manual testing with mosquitto client

This walkthrough triggers real DB writes using only `mosquitto_pub` and `psql`.

**Prerequisites**

| Component | Default address |
|---|---|
| Artemis MQTT broker | `tcp://localhost:1883` (user/pass: `artemis`/`artemis`) |
| PostgreSQL | `localhost:5432/moqui` (user/pass: `moqui`/`moqui`) |
| moqui-device-gateway | running with `%integration` profile |

**Step 1 — start the infrastructure**

```bash
# Standalone DB (see docker/db-compose.yml)
cd moqui-device-gateway
docker compose -f docker/db-compose.yml up -d

# Or, when using the full Moqui stack (moqui-framework/docker):
# docker compose -f postgres-compose.yml -p moqui up -d moqui-database
# docker compose -f activemq-compose.yml -p moqui up -d
```

**Step 2 — verify seed data exists**

The integration profile seed data (`device-gateway-seed.sql`) must be loaded.
Check that the static consumer topic and parameters are present:

```bash
psql -U moqui -h localhost moqui -c "SELECT PARAMETER_ID FROM PARAMETER WHERE PARAMETER_ID LIKE 'VPL_%';"
```

Expected rows include `VPL_PARAM_FEEDBACK`, `VPL_PARAM_FAULT`, etc.

**Step 3 — start the gateway with the integration profile**

```bash
cd moqui-device-gateway
mvn quarkus:dev -Dquarkus.profile=integration
```

The static MQTT consumer starts on `paho-mqtt5:iot/parameters/in?brokerUrl=tcp://localhost:1883&...`.
You should see a log line like:

```
Starting route mqtt-read-device-request-consumer
```

**Step 4 — publish a logging payload (inserts a PARAMETER_LOG row)**

```bash
mosquitto_pub \
  -h localhost -p 1883 \
  -u artemis -P artemis \
  -t iot/parameters/in \
  -m '{"parameterId":"VPL_PARAM_FEEDBACK","numericValue":87.3,"purposeEnumId":"DrpLogging"}'
```

Verify the insert:

```bash
psql -U moqui -h localhost moqui \
  -c "SELECT PARAMETER_ID, NUMERIC_VALUE, OBSERVED_DATE FROM PARAMETER_LOG WHERE PARAMETER_ID='VPL_PARAM_FEEDBACK' ORDER BY OBSERVED_DATE DESC LIMIT 3;"
```

**Step 5 — publish a state-update payload (updates the PARAMETER row)**

```bash
mosquitto_pub \
  -h localhost -p 1883 \
  -u artemis -P artemis \
  -t iot/parameters/in \
  -m '{"parameterId":"VPL_PARAM_FAULT","symbolicValue":"Y","purposeEnumId":"DrpControl"}'
```

Verify the update:

```bash
psql -U moqui -h localhost moqui \
  -c "SELECT PARAMETER_ID, SYMBOLIC_VALUE, LAST_UPDATED_STAMP FROM PARAMETER WHERE PARAMETER_ID='VPL_PARAM_FAULT';"
```

**Step 6 — publish a batch payload**

```bash
mosquitto_pub \
  -h localhost -p 1883 \
  -u artemis -P artemis \
  -t iot/parameters/in \
  -m '[{"parameterId":"VPL_PARAM_FEEDBACK","numericValue":99.9,"purposeEnumId":"DrpLogging"},{"parameterId":"VPL_PARAM_FAULT","symbolicValue":"N","purposeEnumId":"DrpControl"}]'
```

**Step 7 — trigger a dynamic MQTT subscription via REST**

Register a live subscription for a `DrtCyclic` request (the gateway will create a dynamic consumer):

```bash
curl -s -X POST http://localhost:8081/api/device-request/subscribe/VPL_MQTT_SUB_REQ_TEST \
  -H 'X-API-Key: change-me-in-production'
```

With `gateway.api.auth.enabled=false` (integration profile), the header is not required.

The response lists the created route IDs:

```json
{"status":"completed","routeIdList":["device-request-consumer-VPL_MQTT_SUB_REQ_TEST-0"]}
```

Now publish directly to the topic the subscription route is listening on (from `DEVICE_REQUEST_ITEM.QUERY`):

```bash
mosquitto_pub \
  -h localhost -p 1883 \
  -u artemis -P artemis \
  -t virtual_plc_feedback \
  -m '321.5'
```

Verify in DB:

```bash
psql -U moqui -h localhost moqui \
  -c "SELECT PARAMETER_ID, NUMERIC_VALUE FROM PARAMETER_LOG WHERE PARAMETER_ID='VPL_PARAM_FEEDBACK' ORDER BY OBSERVED_DATE DESC LIMIT 1;"
```

**Step 8 — trigger an outbound MQTT write via REST**

Writes current `PARAMETER` values to the configured MQTT topics:

```bash
curl -s -X POST http://localhost:8081/api/device-request/run/VPL_MQTT_PUBLISH_REQ_TEST
```

Subscribe on another terminal to see what the gateway publishes:

```bash
mosquitto_sub \
  -h localhost -p 1883 \
  -u artemis -P artemis \
  -t 'virtual_plc_#' -v
```

**Step 9 — check the health endpoint**

```bash
curl -s http://localhost:8081/q/health/ready | python3 -m json.tool
```

The `gateway-subscriptions` readiness check reports:
- `dynamicRoutesRunning`: how many dynamic consumer routes are started
- `registeredSubscriptions`: how many subscription `requestName` values are persisted
- `missingSubscriptions`: any persisted subscriptions whose routes are not running
- `stoppedRoutes`: any routes registered but not in started state

---

## Integration test baseline

```bash
# Option A — standalone DB only (no full Moqui stack required)
cd moqui-device-gateway
docker compose -f docker/db-compose.yml up -d
mvn quarkus:dev -Dquarkus.profile=integration \
    -Dquarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5434/moqui

# Option B — full Moqui stack (PostgreSQL + Artemis via moqui-framework/docker)
cd moqui-framework/docker
docker compose -f postgres-compose.yml -p moqui up -d moqui-database
docker compose -f activemq-compose.yml -p moqui up -d
# Load schema + seed data (once)
cd moqui-framework && ./gradlew load
# Run gateway
cd moqui-device-gateway
mvn quarkus:dev -Dquarkus.profile=integration
```

The `%integration` profile in `application.properties` activates:

- live PostgreSQL connection
- real Artemis MQTT broker endpoints
- file output under `target/test-recipes`
- DEBUG log level for Camel and gateway categories

---

## Final production checklist

### Configuration

- API key injected through Docker secret; `gateway.api.auth.token` not left at development default
- MQTT endpoints configured for the real Artemis deployment
- Database JDBC URL, credentials, and pool sizes set for PostgreSQL / TimescaleDB or YugabyteDB
- `quarkus.datasource.jdbc.acquisition-timeout` low enough to fail fast during DB outages (2 S default is appropriate)
- `gateway.inbound.error.notification.uri` set if Moqui callback notifications are required
- Log path and rotation aligned with the container filesystem policy

### Runtime behaviour

- Inbound MQTT route continues running when DB persistence fails temporarily
- Inbound OPC UA route continues running when DB persistence fails temporarily
- Malformed inbound payloads are discarded without stopping the route
- Outbound MQTT write failures return an application error without degrading the Camel runtime
- Outbound OPC UA write failures return an application error without degrading the Camel runtime
- Dynamic subscribe and unsubscribe behave correctly across repeated activate / deactivate cycles
- Active subscriptions are restored automatically on gateway restart (`gateway.subscription.registry.path`)
- `InboundErrorNotifier` sends and clears notifications correctly when DB connectivity alternates

### Load and sizing

- Gateway sustains the expected ~500 measures/second/PLC peak profile for the intended PLC count per container
- Async logging queues stay bounded and do not materially affect throughput under error bursts
- SEDA callback queues remain bounded under peak throughput
- CPU, heap, and connection pool sizing validated for the selected deployment size
- Horizontal scaling rule defined (one container per PLC group is the expected model)

### MQTT validation (requires Docker infrastructure)

Use `moqui-framework/docker/activemq-compose.yml` and `postgres-compose.yml`:

- Normal MQTT ingest to DB
- DB outage during MQTT ingest: verify payload discard and route survival
- Artemis restart / temporary unreachability during outbound publish
- Burst test around the expected peak rate

### OPC UA validation

The integration suite uses an embedded Eclipse Milo server (`MiloTestServer`) that validates
the full `milo-client → gateway → database` subscribe path and the `gateway → OPC UA node`
write path including connection-establishment retry.

Production validation should additionally cover:

- Real OPC UA server (hardware or software PLC) replacing the embedded test server
- Node-id mapping from `DEVICE_REQUEST_ITEM.QUERY` against the real address space
- DB outage during OPC UA ingest: verify event discard and route survival
- Reconnect behaviour after OPC UA endpoint interruption
- Subscription recovery after broker or endpoint restart

### Observability

- Warning / error logs visible from container runtime or log collector
- Health endpoint `/q/health` monitored
- Alerting in place for repeated DB and broker connectivity failures
- Operators understand that inbound live data is best-effort during DB outages

### Go / no-go rule

The gateway is production-ready only after MQTT and OPC UA live-path validation pass
against real infrastructure, not only unit tests and route bootstrap tests.
