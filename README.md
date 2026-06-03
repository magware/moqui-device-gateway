# moqui-device-gateway

`moqui-device-gateway` is a **model-first, model-driven, and data-driven industrial edge gateway** for devices modeled with the `moqui-device` component.

Unlike flow-diagram-first tools, where the developer manually builds behavior with ad hoc drag-and-drop flows, this gateway starts from a **shared, relational, industrial device model**. Devices, PLCs, parameters, requests, subscriptions, transport endpoints, gateway ownership, recipes, device configurations, and runtime activation rules are declared as structured data in the Moqui database. Apache Camel routes are then generated and activated as runtime projections of that model.

The result is a different class of gateway:

- **model-first**: the `moqui-device`, a complete Device Lifecycle Management (DLM) / Asset Management data model for industrial/IoT devices, serves as the single source of truth, providing a stronger and more auditable foundation for data consistency, traceability, operational governance, and cybersecurity review. Using the database as the operational declaration layer gives stronger governance than hand-built runtime diagrams.
- **data-driven**: seed data and database rows determine what the gateway does;
- **AI-friendly**: AI agents can inspect, validate, generate, and load structured device data instead of trying to reason over arbitrary visual diagrams;
- **integration-rich**: Apache Camel provides a large ecosystem of components and Enterprise Integration Patterns (EIP), so the same model can drive MQTT, OPC UA, file transfer, database persistence, and future protocols;
- **cloud/edge ready**: Quarkus provides fast startup, Java 21 virtual-thread support, container deployment, and optional GraalVM native compilation.

This is the key differentiation: the gateway does not ask developers to draw fragile runtime flows by hand. It lets engineers and AI agents work against a validated industrial data model, then uses Camel to execute that model safely at the edge.

The Moqui database is the source of truth. Camel routes are runtime projections of the model. Local files are not the source of truth for subscriptions.

---

## Why this is different from diagram-first gateways

Tools such as Node-RED are useful for quick visual prototyping, but their primary artifact is the diagram. At industrial scale this can become difficult to review, secure, version, validate, and generate automatically. The logic is often distributed across visual nodes and manual wiring decisions.

`moqui-device-gateway` uses the opposite approach:

```text
Industrial device model -> validated seed data -> runtime Camel routes
```

This gives several advantages:

| Area | Diagram-first gateway | moqui-device-gateway |
|---|---|---|
| Source of truth | Visual flows | `moqui-device` relational model |
| PLC/device ownership | Often implicit in the flow | Explicit through `DeviceGroup` / `DeviceGroupMember` |
| Subscription restore | Usually flow/runtime state | Active `DeviceRequest` rows in the database |
| AI support | Harder: agents must interpret diagrams | Easier: agents inspect and generate structured data |
| Review and validation | Manual visual review | SQL/model validation, tests, and AI-assisted checks |
| Protocol ecosystem | Depends on installed visual nodes | Apache Camel components and EIP |
| Deployment | Tool-specific runtime | Quarkus JVM/native containers |

The goal is not to replace every quick prototyping tool. The goal is to provide a safer and more deterministic gateway architecture for production industrial systems, where devices, PLCs, telemetry, subscriptions, recipes, and writes must remain consistent over time.

---

## AI-ready engineering workflow

Because the gateway is driven by model data, AI agents can participate safely in the engineering workflow without becoming the source of truth. The agent does not need to reverse-engineer a visual flow or edit opaque runtime wiring. It can operate on explicit entities, relationships, enumerations, seed XML, SQL checks, and tests.

This makes the gateway suitable for AI-assisted industrial engineering workflows such as:

- reviewing whether a gateway is correctly associated with its PLCs through `DeviceGroup` and `DeviceGroupMember`;
- checking whether `DeviceRequest` and `DeviceRequestItem` rows are complete before a route is activated;
- generating or reviewing seed data for devices, parameters, requests, and subscriptions;
- detecting missing gateway identity, missing PLC membership, invalid request scope, or incomplete transport configuration;
- producing repeatable activation and test procedures from the model;
- supporting safer deployment reviews before Camel routes run at the edge.

The important point is not that AI replaces engineering validation. The point is that a structured industrial model gives AI agents a precise and reviewable control surface. With diagram-first tools, the agent usually has to reason over informal visual wiring. With `moqui-device-gateway`, the agent reasons over database-backed model entities and executable tests.

---

## What this component does

`moqui-device-gateway` runs outside the main Moqui web application, close to PLCs, brokers, OPC UA servers, and OT network resources.

It can:

- execute `DeviceRequest` rows defined in the `moqui-device` model;
- publish MQTT messages from `Parameter` values;
- subscribe to MQTT topics and store inbound values;
- read, write, or subscribe to OPC UA nodes;
- ingest PLC diagnostic logs;
- export device configuration, recipe, and batch-management data;
- transfer device content when configured (CNC G-Code file, binary code, upgrade, docs, etc.);
- expose REST endpoints to trigger configured requests;
- restore startup subscriptions from the Moqui database.

It does **not**:

- replace Moqui;
- define the canonical device model by itself;
- use local JSON files as the subscription source of truth;
- require hand-built flow diagrams as the primary configuration mechanism;
- decide which PLC belongs to which gateway by hard-coded Java logic.

---

## Runtime architecture

```text
Moqui database
  └─ moqui-device model
      ├─ Device / PhysicalDevice
      ├─ DeviceGroup / DeviceGroupMember
      ├─ DeviceRequest / DeviceRequestItem
      ├─ ParameterDef / Parameter / ParameterLog
      ├─ DeviceConfigSet / DeviceConfig (recipe and batch-management definitions)
      ├─ DeviceRuleSet / DeviceRule (recipe/configuration instantiation and application)
      └─ DeviceConnection
            ↓
moqui-device-gateway
  └─ Quarkus + Apache Camel runtime routes
            ↓
MQTT / OPC UA / PLC / PostgreSQL / file transfer
```

The gateway does not hard-code PLCs, parameters, MQTT topics, OPC UA nodes, or device recipes. It reads model rows and creates runtime behavior from them.

The main rule is:

```text
moqui-device model rows = persistent declaration
Camel routes             = runtime execution
```

---

## moqui-device model used by the gateway

| Entity | Purpose in the gateway |
|---|---|
| `Device` | Logical identity of a gateway, PLC, controller, IIoT controller, remote IO, drive/inverter, soft starter, servodrive or other modeled device. |
| `PhysicalDevice` | Physical representation of a gateway, PLC, controller, remote IO, drive/inverter, soft starter, servodrive, broker adapter, or other real device. |
| `DeviceGroup` | Logical group that associates an edge gateway with the PLCs/devices it is responsible for. |
| `DeviceGroupMember` | Membership and role of each device inside a `DeviceGroup`, including `DgmpEdgeGateway` for the gateway process. |
| `DeviceRequest` | Declarative request to execute: write, subscribe, unsubscribe, export, transfer, and similar operations. |
| `DeviceRequestItem` | Parameters, topics, node IDs, or other item-level targets belonging to a request. |
| `ParameterDef` | Definition of a machine, process, telemetry, command, recipe, or configuration parameter. |
| `Parameter` | Current value or state of a parameter. |
| `ParameterLog` | Historical/inbound values written by telemetry routes. |
| `DeviceConfigSet` | Group of device configurations, typically used to represent recipe and batch-management definitions. |
| `DeviceConfig` | Machine-side configuration or recipe definition: a set of target values, limits, modes, or settings for a device/process. |
| `DeviceRuleSet` | Rule/configuration set used to instantiate or apply a recipe/configuration to a specific device or production context. |
| `DeviceRule` | Concrete rule or binding that connects a configured process/device behavior to runtime parameters and actions. |
| `DeviceConnection` | Optional transport connection details, especially useful for OPC UA and direct device links on fieldbus protocols. |

In this model, a recipe is not a separate hand-coded gateway flow. A recipe is a machine-side `DeviceConfig`: a structured configuration of parameters and rules that can be stored, reviewed, versioned, exported, and applied through the same model used for devices, telemetry, and requests. This aligns the gateway with established recipe and batch-management concepts, where configuration, execution, and traceability must stay aligned.

The gateway assumes the real production schema and seed lifecycle are managed by Moqui and the `moqui-device` component. The SQL files under `src/test/resources` are local test fixtures and examples only.

---

## Gateway to PLC association

The gateway itself is modeled as a `Device` + `PhysicalDevice`.

A PLC is also modeled as a `Device` + `PhysicalDevice`.

The association between a gateway and the PLCs it serves is modeled through `DeviceGroup` and `DeviceGroupMember`.

Example:

```text
DeviceGroup:
  DG_LINE_01_EDGE

DeviceGroupMember:
  DG_LINE_01_EDGE / GW_EDGE_01  / DgmpEdgeGateway
  DG_LINE_01_EDGE / PLC_LINE_01 / DgmpProcessPLC
  DG_LINE_01_EDGE / PLC_LINE_02 / DgmpProcessPLC
```

The important enumeration is:

```xml
<moqui.basic.Enumeration
    enumId="DgmpEdgeGateway"
    description="Edge Gateway"
    enumTypeId="DeviceGroupMemberPurpose"/>
```

Meaning:

- `DgmpEdgeGateway` identifies the gateway process responsible for a group;
- `DgmpProcessPLC`, `DgmpSafetyPLC`, `DgmpController`, `DgmpRemoteIO`, and similar values identify target PLC/controller/remote-IO members;
- `DeviceRequest.deviceId` remains the target device, usually the PLC;
- `DeviceRequest.brokerUri` remains the broker or transport endpoint;
- `DeviceRequest.query` and/or `DeviceRequestItem.query` remain the topic, node, or target address.

`DgmpEdgeGateway` is not the MQTT broker. It is the edge process that executes the request. The broker is still represented by the request transport fields, such as `brokerUri`, or by `DeviceConnection` where appropriate.

---

## Startup subscription discovery

At startup or after a failure recovery, the gateway discovers active subscriptions from the Moqui database.

Startup discovery follows this chain:

```text
GATEWAY_DEVICE_ID
  -> Device + PhysicalDevice validation
  -> DeviceGroupMember with purposeEnumId = DgmpEdgeGateway
  -> target PLC/controller/remote-IO members in the same DeviceGroup
  -> active DeviceRequest rows routed through DrrMoquiDeviceGateway
  -> dynamic Camel routes
```

In practical terms, the gateway starts only from its logical identity, `GATEWAY_DEVICE_ID`. From that identity it finds the PLCs/controllers it is responsible for, then loads the active `DeviceRequest` rows that belong to those target devices. The result is a set of Camel routes created from the model at runtime.

Manual REST execution follows the same ownership rule: a gateway can execute only requests belonging to devices inside its own `DeviceGroup` scope.

---

## Runtime configuration

The most important runtime settings are:

| Variable / property | Required | Description |
|---|---:|---|
| `GATEWAY_DEVICE_ID` / `gateway.device.id` | Yes | `PhysicalDevice` ID of this gateway, for example `GW_EDGE_01`. |
| `QUARKUS_DATASOURCE_JDBC_URL` | Yes | JDBC URL of the Moqui database. |
| `QUARKUS_DATASOURCE_USERNAME` | Yes | Database user. |
| `QUARKUS_DATASOURCE_PASSWORD` | Yes | Database password. |
| `QUARKUS_DATASOURCE_LOG_JDBC_URL` | Optional | Telemetry/log datasource. In small test setups it can point to the same DB. |
| `GATEWAY_API_AUTH_TOKEN` | Required when API auth is enabled | REST API token. Do not use the default token in production. |
| `QUARKUS_HTTP_PORT` | Optional | HTTP port, default `8081`. |

Example:

```bash
export GATEWAY_DEVICE_ID=GW_EDGE_01
export QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/moqui
export QUARKUS_DATASOURCE_USERNAME=moqui
export QUARKUS_DATASOURCE_PASSWORD=moqui
export GATEWAY_API_AUTH_TOKEN='replace-with-a-real-secret'
```

`GATEWAY_DEVICE_ID` must exist as `Device` + `PhysicalDevice` and must belong to at least one `DeviceGroup` as `DgmpEdgeGateway`.

---

## Quick start for developers

### 1. Build

```bash
mvn clean package
```

### 2. Run fast tests

```bash
mvn test
```

Fast tests are designed to run without the full Moqui runtime and without external MQTT/OPC UA infrastructure where possible.

### 3. Start local PostgreSQL

```bash
docker compose -f docker/postgres-compose.yml -p moqui-gateway up -d
```

The local PostgreSQL Compose file initializes automatically on first startup. It loads:

```text
src/test/resources/db/init.sql
src/test/resources/device-gateway-seed.sql
src/test/resources/device-gateway-opcua-seed.sql
```

The seed templates are expanded with the environment variables defined in `docker/postgres-compose.yml`.

If you want to re-run initialization from scratch:

```bash
docker compose -f docker/postgres-compose.yml -p moqui-gateway down -v
docker compose -f docker/postgres-compose.yml -p moqui-gateway up -d
```

Wait until PostgreSQL is healthy:

```bash
docker ps --filter name=moqui-gateway-database
```

Verify that the local fixture data exists:

```bash
PGPASSWORD=moqui psql -h 127.0.0.1 -p 5432 -U moqui -d moqui \
  -c "select device_id from device order by device_id;"
```

Password for the local compose file:

```text
moqui
```

This creates sample IDs such as:

```text
GW_EDGE_01
VIRTUAL_PLC_01
DG_EDGE_01
VPL_MQTT_PUBLISH_REQ_01
VPL_MQTT_SUB_REQ_01
```

### 4. Start ActiveMQ Artemis for MQTT tests

For local MQTT tests, use the ActiveMQ Artemis broker provided by the standard Moqui Docker setup.

From the repository layout where `moqui-device-gateway` is next to `moqui-framework`, start Artemis with:

```bash
docker compose -f ../moqui-framework/docker/activemq-compose.yml -p moqui-gateway up -d
```

The local seed examples expect an MQTT broker compatible with:

```text
paho-mqtt5:?brokerUrl=tcp://localhost:1883&userName=artemis&password=artemis
```

This is the standard local developer mode:

- PostgreSQL via `docker/postgres-compose.yml`
- ActiveMQ Artemis via `../moqui-framework/docker/activemq-compose.yml`
- `moqui-device-gateway` running directly on the host JVM with Quarkus/Maven

In this standard mode, `DeviceRequest.brokerUri` should use `tcp://localhost:1883`.

Container gateway mode is a separate option. If the gateway itself runs with `docker/device-gateway-compose.yml`, then `DeviceRequest.brokerUri` must use a broker host name reachable from inside the gateway container. That host name depends on the service name and network used by the Moqui framework ActiveMQ Compose file, so it should not be guessed or hard-coded blindly.

Any MQTT broker can be used if the corresponding `DeviceRequest.brokerUri` values are updated, but ActiveMQ Artemis is the reference broker for this component.

### 5. Start the gateway without Docker

For local developer startup, use the `local` profile:

```bash
GATEWAY_DEVICE_ID=GW_EDGE_01 \
java -Dquarkus.profile=local -jar target/quarkus-app/quarkus-run.jar
```

Equivalent Maven/Quarkus dev-style startup:

```bash
GATEWAY_DEVICE_ID=GW_EDGE_01 \
mvn quarkus:dev -Dquarkus.profile=local
```

Default endpoint:

```text
http://localhost:8081
```

If you want to start with the default profile instead, use a real token:

```bash
GATEWAY_DEVICE_ID=GW_EDGE_01 \
GATEWAY_API_AUTH_TOKEN='replace-with-a-real-secret' \
java -jar target/quarkus-app/quarkus-run.jar
```

### 6. Check readiness

```bash
curl http://localhost:8081/q/health/ready
```

Readiness should be `UP` only when the process, database, Camel context, and gateway model identity are valid.

### 7. Trigger a request

```bash
curl -X POST \
  http://localhost:8081/api/device-request/run/VPL_MQTT_PUBLISH_REQ_01 \
  -H 'X-API-Key: change-me'
```

---

## Running scenarios

### Scenario A — MQTT write / publish

Use this when a `DeviceRequest` represents a write/publish operation.

Prerequisites:

- the gateway exists as `Device` + `PhysicalDevice`;
- the target PLC exists as `Device` + `PhysicalDevice`;
- both are in the same `DeviceGroup`;
- the gateway member has `purposeEnumId = DgmpEdgeGateway`;
- the PLC member has a PLC/controller purpose such as `DgmpProcessPLC`;
- a `DeviceRequest` exists with `requestTypeEnumId = DrtWrite`;
- request items exist in `DeviceRequestItem`;
- current values exist in `Parameter`.

These examples use `mosquitto_sub` only as an MQTT client CLI tool. The broker used by the standard local setup is ActiveMQ Artemis.

Watch an outbound MQTT topic:

```bash
mosquitto_sub -h 127.0.0.1 -p 1883 \
  -u artemis -P artemis \
  -t 'mqtt-write-device-request/#'
```

Run the request:

```bash
curl -X POST \
  http://localhost:8081/api/device-request/run/VPL_MQTT_PUBLISH_REQ_01 \
  -H 'X-API-Key: change-me'
```

### Scenario B — MQTT subscription / inbound parameter update

For persistent startup subscriptions, do not edit a local JSON file.

Create or seed an active `DeviceRequest` in the Moqui database and associate the target PLC with the gateway through `DeviceGroupMember`.

Required model rows:

```text
Device + PhysicalDevice: gateway
Device + PhysicalDevice: PLC
DeviceGroup: gateway/PLC scope
DeviceGroupMember: gateway with DgmpEdgeGateway
DeviceGroupMember: PLC with DgmpProcessPLC or similar
DeviceRequest: subscription request routed through DrrMoquiDeviceGateway
DeviceRequestItem: subscription items/topics/parameters where required
```

Start the gateway. It will restore eligible subscription routes from the database.

These examples use `mosquitto_pub` only as an MQTT client CLI tool. The broker used by the standard local setup is ActiveMQ Artemis.

Publish a test inbound message:

```bash
mosquitto_pub -h 127.0.0.1 -p 1883 \
  -u artemis -P artemis \
  -t 'mqtt-subscribe-device-request/virtual-plc/feedback' \
  -m '{"parameterId":"VPL_PARAM_FEEDBACK_01","numericValue":12.3,"purposeEnumId":"DrpLogging"}'
```

Check the database:

```sql
SELECT * FROM PARAMETER WHERE PARAMETER_ID = 'VPL_PARAM_FEEDBACK_01';
SELECT * FROM PARAMETER_LOG ORDER BY OBSERVED_DATE DESC;
```

### Scenario C — OPC UA

Use this when `DeviceRequest` / `DeviceRequestItem` rows reference OPC UA node IDs or `DeviceConnection` rows.

Typical setup:

1. create or seed the gateway and PLC devices;
2. associate them through `DeviceGroup` / `DeviceGroupMember`;
3. create a `DeviceConnection` for the OPC UA endpoint;
4. create `DeviceRequest` and `DeviceRequestItem` rows with OPC UA node IDs;
5. start the OPC UA server or test server;
6. start the gateway;
7. trigger the request manually through the REST API. If you want OPC UA subscriptions to be restored at startup, model them with `routerEnumId = DrrMoquiDeviceGateway` and with a request type supported by startup discovery;
8. verify `Parameter` / `ParameterLog`.

The sample OPC UA seed is:

```text
src/test/resources/device-gateway-opcua-seed.sql
```

It also contains placeholders and must be adapted to the local endpoint and node IDs before use.

### Scenario D — PLC log ingestion

PLC log ingestion routes inbound diagnostic records into the database.

Typical flow:

```text
PLC/logger -> MQTT/broker/topic -> Camel route -> DEVICE_LOG or related log table
```

Use this when PLC runtime logs should be persisted separately from normal parameter telemetry.

Check the configured PLC log topic and payload format in the corresponding `DeviceRequest` and route configuration before testing.

### Scenario E — Device configuration / recipe export

Configuration export is used when Moqui stores a device configuration, recipe, or batch-management definition and the gateway must export it for an external PLC/device workflow. In this model, a recipe is a structured machine-side `DeviceConfig`, not a separate visual flow.

Example REST call:

```bash
curl -X POST \
  http://localhost:8081/api/device-config/export \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: change-me' \
  -d '{
    "deviceRuleSetId":"VPL_RULESET_1_01",
    "deviceId":"VIRTUAL_PLC_01",
    "deviceRuleId":null
  }'
```

### Scenario F — Device content transfer

Use content transfer for file-like payloads such as recipes, device files, or other content that must be sent to a configured destination.

Example:

```bash
curl -X POST \
  http://localhost:8081/api/device-content/transfer/SFTP_TRANSFER_REQ \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: change-me' \
  -d '{
    "filename":"recipe.txt",
    "contentBase64":"SGVsbG8K"
  }'
```

---

## REST API

All REST endpoints are under:

```text
/api
```

When authentication is enabled, pass the API token with either:

```text
X-API-Key: <token>
```

or:

```text
Authorization: Bearer <token>
```

| Endpoint | Purpose |
|---|---|
| `POST /api/device-request/run/{requestName}` | Execute a `DeviceRequest` already defined in the database. |
| `POST /api/device-request/unsubscribe/{requestName}` | Stop the current runtime subscription route for a request. |
| `POST /api/device-config/export` | Export device configuration / recipe data for a device workflow. |
| `POST /api/device-content/transfer/{requestName}` | Transfer configured file-like device content. |

### Run a DeviceRequest

```bash
curl -X POST \
  http://localhost:8081/api/device-request/run/{requestName} \
  -H 'X-API-Key: change-me'
```

This endpoint executes a `DeviceRequest` already defined in the database. It does not define the request.

The gateway enforces scope. A request can run only if its `DEVICE_ID` belongs to a `DeviceGroup` served by the configured `GATEWAY_DEVICE_ID`.

### Unsubscribe

Unsubscribe stops the current runtime route.

It does not permanently deactivate the `DeviceRequest`.

To prevent a startup subscription from being restored after restart, update the persistent model in the Moqui database. Common options are:

- set `DeviceRequest.thruDate`;
- change `DeviceRequest.routerEnumId`;
- remove or disable the relevant `DeviceGroupMember` association.

For request bodies and longer examples, see the running scenarios above.

---

## Testing

### Fast/local tests

```bash
mvn test
```

These tests should not require a complete Moqui runtime.

### Integration tests

Integration tests require external services such as PostgreSQL, MQTT broker, and OPC UA test infrastructure.

The standard local MQTT broker for integration tests is ActiveMQ Artemis from:

```bash
docker compose -f ../moqui-framework/docker/activemq-compose.yml -p moqui-gateway up -d
```

Run examples:

```bash
mvn -Dquarkus.profile=integration -Dtest=MqttInboundIntegrationTest test
mvn -Dquarkus.profile=integration -Dtest=GatewaySeededRouteIntegrationTest test
mvn -Dquarkus.profile=integration -Dtest=OpcUaGatewayIntegrationTest test
mvn -Dquarkus.profile=integration -Dtest=PlcLogIngestIntegrationTest test
```

Or use:

```bash
./scripts/run-integration-tests.sh
```

Before considering the component production-ready for a plant, run at least:

- startup restore from DB;
- MQTT write;
- MQTT subscribe;
- gateway crash/restart;
- broker down/up;
- OPC UA read/write/subscribe if used;
- PLC log ingestion if used;
- readiness behavior with broker/PLC temporarily unreachable.

### Important note about test SQL

`src/test/resources/db/init.sql` is a minimal PostgreSQL schema used by automated tests and local examples.

It is **not** the production schema and it is **not** the canonical `moqui-device` data model.

In production, tables and seed data are managed by Moqui and by the `moqui-device` component.

### Seed examples

`src/test/resources/device-gateway-seed.sql` contains local MQTT-oriented test data.

`src/test/resources/device-gateway-opcua-seed.sql` contains local OPC UA-oriented test data.

They are useful examples, but production seed data should live in Moqui component data files or be inserted through Moqui services/screens.

---

## Docker Compose

This repository includes simple local Compose files:

```text
docker/postgres-compose.yml
docker/device-gateway-compose.yml
```

The broker used by the standard local setup is still ActiveMQ Artemis from the Moqui framework Docker directory:

```bash
docker compose -f ../moqui-framework/docker/activemq-compose.yml -p moqui-gateway up -d
```

Start PostgreSQL:

```bash
docker compose -f docker/postgres-compose.yml -p moqui-gateway up -d
```

The PostgreSQL Compose file auto-loads the local test schema and seed on first startup. If the named volume already exists, initialization is not repeated.

Build and start the gateway:

```bash
docker compose -f docker/device-gateway-compose.yml -p moqui-gateway up -d --build
```

To build the gateway container with the native Dockerfile:

```bash
DEVICE_GATEWAY_DOCKERFILE=src/main/docker/Dockerfile.graalvm \
DEVICE_GATEWAY_IMAGE=moqui-device-gateway:native \
docker compose -f docker/device-gateway-compose.yml -p moqui-gateway up -d --build
```

Check readiness:

```bash
curl http://localhost:8081/q/health/ready
```

Stop services:

```bash
docker compose -f docker/device-gateway-compose.yml -p moqui-gateway down
docker compose -f docker/postgres-compose.yml -p moqui-gateway down
```

Remove volumes too:

```bash
docker compose -f docker/device-gateway-compose.yml -p moqui-gateway down -v
docker compose -f docker/postgres-compose.yml -p moqui-gateway down -v
```

The local Compose files are for development and simple testing. Production deployments should use real Moqui database infrastructure, secrets, backups, network policy, and monitoring.

---

## Docker Swarm deployment

For Swarm, configure the logical gateway identity explicitly:

```yaml
environment:
  - GATEWAY_DEVICE_ID=GW_EDGE_01
```

Rules:

- use one active replica per logical `GATEWAY_DEVICE_ID`;
- multiple simultaneous gateways should be modeled as different `PhysicalDevice` gateway records;
- assign each gateway to its own `DeviceGroup` scope or to clearly controlled shared scopes;
- do not run two active containers with the same `GATEWAY_DEVICE_ID` unless you add an external leader-election or lease mechanism;
- Swarm failover means the same logical gateway is restarted on another node;
- local `/deployments/data` is not the subscription source of truth;
- startup subscriptions are restored from the Moqui database.

Example logical layout:

```text
GW_EDGE_01 -> Line 1 PLCs
GW_EDGE_02 -> Line 2 PLCs
GW_EDGE_03 -> Drying/Maturation cells
```

If this component is checked out inside a larger deployment repository, that repository may provide production-oriented Swarm files with Docker secrets, configs, shared volumes, restart policy, and update policy.

---

## MQTT persistent subscriptions

The gateway can restore subscription routes from the database after restart.

This is not the same thing as MQTT broker-side persistent session delivery.

### DB-driven startup restore

DB-driven startup restore means:

```text
DeviceRequest in DB -> gateway startup discovery -> Camel subscription route
```

It recreates the route after gateway restart.

### MQTT broker-side persistent session

Broker-side persistent MQTT delivery determines whether messages published while the gateway was offline can be delivered after reconnect.

For broker-side persistent subscriptions, all of these must be true:

- stable MQTT `clientId`;
- `cleanStart=false`;
- `sessionExpiryInterval > 0`;
- QoS suitable for the required delivery semantics;
- broker persistence enabled/configured.

When the MQTT URI does not already define a `clientId`, the gateway generates a stable one based on:

```text
GATEWAY_DEVICE_ID + requestName + item index
```

Example:

```text
moqui-gw-GW_EDGE_01-VPL_MQTT_SUB_REQ_01-0
```

Do not use container ID or hostname as MQTT `clientId` if the subscription must survive Swarm failover. Those values can change when the container is rescheduled.

---

## Health check

Use:

```text
/q/health/ready
```

Readiness checks whether the gateway process can operate as a gateway process.

It checks:

- process is running;
- Camel context is started;
- database is reachable;
- `GATEWAY_DEVICE_ID` is configured;
- the gateway exists as `Device` + `PhysicalDevice`;
- the gateway belongs to at least one `DeviceGroup` as `DgmpEdgeGateway`.

Readiness does **not** mean every PLC, MQTT broker, or OPC UA server is currently reachable.

Readiness should not go `DOWN` only because a remote PLC or MQTT broker is temporarily unavailable. Field-level failures should be handled through MQTT Last Will messages, route error handling, logs, and optional diagnostic records in Moqui.

---

## Security notes

For production:

- replace `GATEWAY_API_AUTH_TOKEN` with a real secret;
- do not deploy with `change-me` or `change-me-in-production`;
- use Docker secrets or an equivalent secret manager;
- restrict network exposure of port `8081`;
- expose the gateway only to trusted OT/IT networks;
- use TLS where required by the plant network policy;
- monitor logs and failed route starts.

---

## Troubleshooting

| Symptom | Likely cause | What to check |
|---|---|---|
| `/q/health/ready` is `DOWN` | Missing or invalid gateway identity | Check `GATEWAY_DEVICE_ID`, `Device`, `PhysicalDevice`, `DeviceGroupMember`. |
| Gateway starts but restores no routes | Gateway is not linked to PLCs through a valid group | Check `DgmpEdgeGateway`, target PLC membership, `STATUS_ID`, `routerEnumId`, and dates. |
| Manual request is rejected | Request belongs to a PLC outside this gateway scope | Check `DeviceRequest.deviceId` and `DeviceGroupMember` associations. |
| Request returns an error or no route starts | Invalid broker URI, topic, OPC UA node, connection, or Camel endpoint | Check `DeviceRequest`, `DeviceRequestItem`, `DeviceConnection`, and logs. |
| MQTT messages are not received after restart | Broker persistent session is not configured | Check `clientId`, `cleanStart`, `sessionExpiryInterval`, QoS, and broker persistence. |
| Inbound value is not stored | Payload does not match expected parameter mapping | Check payload, `parameterId`, `Parameter`, and `ParameterLog`. |
| Duplicate MQTT subscriptions | Two active containers use the same `GATEWAY_DEVICE_ID` | Use one active replica per gateway ID unless leader election is added. |
| Local Docker gateway is not ready | Test schema or seed not loaded | Load `init.sql`, adapt/load seed data, and verify `GW_EDGE_01` exists. |

Useful SQL checks:

```sql
SELECT * FROM DEVICE WHERE DEVICE_ID = 'GW_EDGE_01';
SELECT * FROM PHYSICAL_DEVICE WHERE DEVICE_ID = 'GW_EDGE_01';
SELECT * FROM DEVICE_GROUP_MEMBER WHERE MEMBER_DEVICE_ID = 'GW_EDGE_01';
SELECT * FROM DEVICE_REQUEST ORDER BY REQUEST_NAME;
SELECT * FROM DEVICE_REQUEST_ITEM ORDER BY REQUEST_NAME, SEQUENCE_NUM;
SELECT * FROM PARAMETER ORDER BY PARAMETER_ID;
SELECT * FROM PARAMETER_LOG ORDER BY OBSERVED_DATE DESC;
```

---

## Scope and limitations

This README describes operational usage, model configuration, local testing, and deployment principles.

It intentionally does not document every internal Java class or every Camel route implementation detail.

The important operational rule is:

```text
The model defines what the gateway may do.
The REST API and startup discovery activate what the model declares.
Camel routes execute the model at runtime.
```
