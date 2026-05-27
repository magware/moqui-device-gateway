## Device Request Seed Templates

This document extracts the reusable seed-data patterns used by:

- `moqui-device-gateway` integration tests
- `moqui-device` gateway bridge services
- example conventions already present in `DeviceGatewayServices.xml`

The goal is to provide stable templates for a future automation skill that:

1. reads a device/request specification,
2. decides which request shapes are needed,
3. generates SQL seed data,
4. populates the shared Moqui database.

## Template Families

There are two layers of seed data:

- `gateway-side` requests
  - consumed directly by `moqui-device-gateway`
  - contain the real fieldbus details: MQTT topics, OPC UA nodes, file transfer URI, etc.
- `moqui-side` wrapper requests
  - consumed by `moqui.device.DeviceServices.run#DeviceRequest`
  - dispatch to `moqui.device.DeviceGatewayServices.*`
  - point to the gateway REST base URL and the target gateway-side `requestName`

In practice:

- the gateway-side request describes *how to talk to the field device*
- the Moqui-side request describes *how to invoke the gateway*

## Naming Conventions

Use short, explicit names and keep them under the `text-short` limit of `DeviceRequest.requestName`.

Recommended prefixes:

- `VPL_...` for gateway-side device requests
- `GW1_...` for Moqui-side wrapper requests
- `..._REQ` for active requests
- `..._UNSUB_REQ` for unsubscribe requests

## Shared Base Entities

Most request templates assume these entities already exist:

- `DEVICE`
- `PHYSICAL_DEVICE`
- `PARAMETER_DEF`
- `PARAMETER`

Minimal pattern:

```sql
INSERT INTO DEVICE (DEVICE_ID, DEVICE_TYPE_ENUM_ID)
VALUES ('__DEVICE_ID__', '__DEVICE_TYPE_ENUM_ID__');

INSERT INTO PHYSICAL_DEVICE (DEVICE_ID, DEVICE_NAME)
VALUES ('__DEVICE_ID__', '__DEVICE_NAME__');

INSERT INTO PARAMETER_DEF (PARAMETER_DEF_ID, PARAMETER_TYPE_ENUM_ID, PARAMETER_CODE, PARAMETER_NAME)
VALUES ('__PARAMETER_DEF_ID__', '__PARAMETER_TYPE_ENUM_ID__', '__PARAMETER_CODE__', '__PARAMETER_NAME__');

INSERT INTO PARAMETER (PARAMETER_ID, PARAMETER_DEF_ID, DEVICE_ID, NUMERIC_VALUE)
VALUES ('__PARAMETER_ID__', '__PARAMETER_DEF_ID__', '__DEVICE_ID__', 0.0);
```

## Gateway-Side Templates

### MQTT Write

Use for broker-managed outbound writes.

Required fields:

- `REQUEST_TYPE_ENUM_ID = DrtWrite`
- `ROUTER_ENUM_ID = DrrBroker`
- `BROKER_URI = paho-mqtt5:?...`
- `ONLY_CHANGED_PARAMETERS = N` if the gateway must always publish all mapped items

Pattern:

```sql
INSERT INTO DEVICE_REQUEST (
    REQUEST_NAME, DEVICE_ID, REQUEST_TYPE_ENUM_ID, PURPOSE_ENUM_ID,
    ROUTER_ENUM_ID, BROKER_URI, ONLY_CHANGED_PARAMETERS
) VALUES (
    '__GW_REQ_NAME__', '__DEVICE_ID__', 'DrtWrite', '__PURPOSE_ENUM_ID__',
    'DrrBroker', '__MQTT_PUBLISH_BASE_URI__', 'N'
);

INSERT INTO DEVICE_REQUEST_ITEM (REQUEST_NAME, PARAMETER_ID, SEQUENCE_NUM, QUERY)
VALUES ('__GW_REQ_NAME__', '__PARAMETER_ID_1__', 1, '__TOPIC_1__');

INSERT INTO DEVICE_REQUEST_ITEM (REQUEST_NAME, PARAMETER_ID, SEQUENCE_NUM, QUERY)
VALUES ('__GW_REQ_NAME__', '__PARAMETER_ID_2__', 2, '__TOPIC_2__');
```

### MQTT Subscribe

Use for broker-managed inbound telemetry or status updates.

Required fields:

- `REQUEST_TYPE_ENUM_ID = DrtCyclic` or another subscribe subtype
- `ROUTER_ENUM_ID = DrrBroker`
- `BROKER_URI = paho-mqtt5:?...`
- `POLLING_INTERVAL` is required by the current data model convention even for MQTT

Pattern:

```sql
INSERT INTO DEVICE_REQUEST (
    REQUEST_NAME, DEVICE_ID, REQUEST_TYPE_ENUM_ID, PURPOSE_ENUM_ID,
    ROUTER_ENUM_ID, BROKER_URI, POLLING_INTERVAL
) VALUES (
    '__GW_SUB_REQ_NAME__', '__DEVICE_ID__', 'DrtCyclic', '__PURPOSE_ENUM_ID__',
    'DrrBroker', '__MQTT_SUBSCRIBE_BASE_URI__', 100
);

INSERT INTO DEVICE_REQUEST_ITEM (REQUEST_NAME, PARAMETER_ID, SEQUENCE_NUM, QUERY)
VALUES ('__GW_SUB_REQ_NAME__', '__PARAMETER_ID_1__', 1, '__TOPIC_1__');

INSERT INTO DEVICE_REQUEST_ITEM (REQUEST_NAME, PARAMETER_ID, SEQUENCE_NUM, QUERY)
VALUES ('__GW_SUB_REQ_NAME__', '__PARAMETER_ID_2__', 2, '__TOPIC_2__');
```

### MQTT Unsubscribe

Use to stop dynamic routes created by the subscribe request.

Required fields:

- `REQUEST_TYPE_ENUM_ID = DrtUnsubscribe`
- `PARENT_REQUEST_NAME = subscribe request name`

Pattern:

```sql
INSERT INTO DEVICE_REQUEST (
    REQUEST_NAME, PARENT_REQUEST_NAME, DEVICE_ID, REQUEST_TYPE_ENUM_ID,
    PURPOSE_ENUM_ID, ROUTER_ENUM_ID, BROKER_URI
) VALUES (
    '__GW_UNSUB_REQ_NAME__', '__GW_SUB_REQ_NAME__', '__DEVICE_ID__', 'DrtUnsubscribe',
    '__PURPOSE_ENUM_ID__', 'DrrBroker', '__MQTT_SUBSCRIBE_BASE_URI__'
);
```

### OPC UA Subscribe / Read

Use for dynamic OPC UA monitored items.

Required fields:

- `REQUEST_TYPE_ENUM_ID = DrtCyclic` for long-lived monitored routes
- `ROUTER_ENUM_ID = DrrDirect`
- `CONNECTION_NAME = __OPCUA_CONNECTION_NAME__`

Pattern:

```sql
INSERT INTO DEVICE_REQUEST (
    REQUEST_NAME, DEVICE_ID, REQUEST_TYPE_ENUM_ID, PURPOSE_ENUM_ID,
    ROUTER_ENUM_ID, CONNECTION_NAME, POLLING_INTERVAL
) VALUES (
    '__GW_OPCUA_SUB_REQ__', '__DEVICE_ID__', 'DrtCyclic', '__PURPOSE_ENUM_ID__',
    'DrrDirect', '__OPCUA_CONNECTION_NAME__', 100
);

INSERT INTO DEVICE_REQUEST_ITEM (REQUEST_NAME, PARAMETER_ID, SEQUENCE_NUM, QUERY)
VALUES ('__GW_OPCUA_SUB_REQ__', '__PARAMETER_ID_1__', 1, '__OPCUA_NODE_1__');

INSERT INTO DEVICE_REQUEST_ITEM (REQUEST_NAME, PARAMETER_ID, SEQUENCE_NUM, QUERY)
VALUES ('__GW_OPCUA_SUB_REQ__', '__PARAMETER_ID_2__', 2, '__OPCUA_NODE_2__');
```

For one-shot OPC UA reads, reuse the same item structure with:

- `REQUEST_TYPE_ENUM_ID = DrtRead`
- `POLLING_INTERVAL = null`

### OPC UA Unsubscribe

Pattern:

```sql
INSERT INTO DEVICE_REQUEST (
    REQUEST_NAME, PARENT_REQUEST_NAME, DEVICE_ID, REQUEST_TYPE_ENUM_ID,
    PURPOSE_ENUM_ID, ROUTER_ENUM_ID, CONNECTION_NAME
) VALUES (
    '__GW_OPCUA_UNSUB_REQ__', '__GW_OPCUA_SUB_REQ__', '__DEVICE_ID__', 'DrtUnsubscribe',
    '__PURPOSE_ENUM_ID__', 'DrrDirect', '__OPCUA_CONNECTION_NAME__'
);
```

### OPC UA Write

Pattern:

```sql
INSERT INTO DEVICE_REQUEST (
    REQUEST_NAME, DEVICE_ID, REQUEST_TYPE_ENUM_ID, PURPOSE_ENUM_ID,
    ROUTER_ENUM_ID, CONNECTION_NAME, ONLY_CHANGED_PARAMETERS
) VALUES (
    '__GW_OPCUA_WRITE_REQ__', '__DEVICE_ID__', 'DrtWrite', '__PURPOSE_ENUM_ID__',
    'DrrDirect', '__OPCUA_CONNECTION_NAME__', 'N'
);

INSERT INTO DEVICE_REQUEST_ITEM (REQUEST_NAME, PARAMETER_ID, SEQUENCE_NUM, QUERY)
VALUES ('__GW_OPCUA_WRITE_REQ__', '__PARAMETER_ID_1__', 1, '__OPCUA_WRITE_NODE_1__');
```

### Content Transfer

Gateway-side content transfer uses:

- `REQUEST_TYPE_ENUM_ID = DrtContentTransfer`
- `BROKER_URI = sftp:...` or `file:...`

Pattern:

```sql
INSERT INTO DEVICE_REQUEST (
    REQUEST_NAME, DEVICE_ID, REQUEST_TYPE_ENUM_ID, PURPOSE_ENUM_ID,
    ROUTER_ENUM_ID, BROKER_URI
) VALUES (
    '__GW_TRANSFER_REQ__', '__DEVICE_ID__', 'DrtContentTransfer', '__PURPOSE_ENUM_ID__',
    'DrrBroker', '__TRANSFER_URI__'
);
```

## Moqui-Side Wrapper Templates

### Generic Gateway Dispatch Wrapper

This is the standard wrapper for:

- `DrtWrite`
- `DrtRead`
- `DrtCyclic`
- `DrtSubscribe`
- `DrtStateChange`
- `DrtEvent`
- `DrtUnsubscribe`

Pattern:

```sql
INSERT INTO DEVICE (DEVICE_ID, DEVICE_TYPE_ENUM_ID)
VALUES ('__GATEWAY_DEVICE_ID__', 'DtIoTGateway');

INSERT INTO PHYSICAL_DEVICE (DEVICE_ID, DEVICE_NAME)
VALUES ('__GATEWAY_DEVICE_ID__', '__GATEWAY_DEVICE_NAME__');

INSERT INTO DEVICE_REQUEST (
    REQUEST_NAME, DEVICE_ID, ROUTER_ENUM_ID, REQUEST_TYPE_ENUM_ID,
    RUN_SERVICE_NAME, BROKER_URI, QUERY, TIMEOUT, ONLY_CHANGED_PARAMETERS
) VALUES (
    '__MOQUI_REQ_NAME__', '__GATEWAY_DEVICE_ID__', 'DrrMoquiDeviceGateway', '__REQUEST_TYPE_ENUM_ID__',
    'moqui.device.DeviceGatewayServices.run#GatewayDeviceRequest',
    '__GATEWAY_BASE_URL__',
    '__GW_TARGET_REQUEST_NAME__',
    30,
    'N'
);

INSERT INTO DEVICE_REQUEST_ITEM (REQUEST_NAME, PARAMETER_ID, SEQUENCE_NUM, QUERY)
VALUES ('__MOQUI_REQ_NAME__', '__ANY_PARAMETER_ID_ON_TARGET_DEVICE__', 1, '__OPTIONAL_QUERY_HINT__');
```

Notes:

- the wrapper needs at least one `DEVICE_REQUEST_ITEM`, otherwise `run#DeviceRequest` returns early
- for OPC UA wrappers, `CONNECTION_NAME` may also be set to mirror the target connection metadata
- `QUERY` is the gateway-side `requestName`, not a topic or OPC UA node

### Export Device Config Wrapper

Pattern:

```sql
INSERT INTO DEVICE_REQUEST (
    REQUEST_NAME, DEVICE_ID, ROUTER_ENUM_ID, REQUEST_TYPE_ENUM_ID,
    RUN_SERVICE_NAME, BROKER_URI, TIMEOUT
) VALUES (
    '__MOQUI_EXPORT_REQ__', '__GATEWAY_DEVICE_ID__', 'DrrMoquiDeviceGateway', 'DrtConfigWrite',
    'moqui.device.DeviceGatewayServices.export#DeviceConfig',
    '__GATEWAY_BASE_URL__',
    30
);
```

This wrapper is invoked with service parameters such as:

- `deviceRuleSetId`
- optional `deviceId`
- optional `deviceRuleId`

### Device Content Transfer Wrapper

Pattern:

```sql
INSERT INTO DEVICE_REQUEST (
    REQUEST_NAME, DEVICE_ID, ROUTER_ENUM_ID, REQUEST_TYPE_ENUM_ID,
    RUN_SERVICE_NAME, BROKER_URI, QUERY, TIMEOUT
) VALUES (
    '__MOQUI_TRANSFER_REQ__', '__GATEWAY_DEVICE_ID__', 'DrrMoquiDeviceGateway', 'DrtContentTransfer',
    'moqui.device.DeviceGatewayServices.transfer#DeviceContent',
    '__GATEWAY_BASE_URL__',
    '__GW_TRANSFER_REQ__',
    30
);
```

This wrapper is invoked with:

- `deviceContentId`
- `requestName`

## Placeholder Catalog for Future Automation

These placeholders are intentionally stable so a future skill can fill them mechanically:

- `__DEVICE_ID__`
- `__DEVICE_NAME__`
- `__DEVICE_TYPE_ENUM_ID__`
- `__PURPOSE_ENUM_ID__`
- `__PARAMETER_DEF_ID__`
- `__PARAMETER_ID__`
- `__REQUEST_TYPE_ENUM_ID__`
- `__GW_REQ_NAME__`
- `__GW_SUB_REQ_NAME__`
- `__GW_UNSUB_REQ_NAME__`
- `__MOQUI_REQ_NAME__`
- `__GATEWAY_DEVICE_ID__`
- `__GATEWAY_DEVICE_NAME__`
- `__GATEWAY_BASE_URL__`
- `__GW_TARGET_REQUEST_NAME__`
- `__MQTT_PUBLISH_BASE_URI__`
- `__MQTT_SUBSCRIBE_BASE_URI__`
- `__OPCUA_CONNECTION_NAME__`
- `__OPCUA_NODE_1__`
- `__TRANSFER_URI__`

## Suggested Skill Workflow

A future `SKILL.md` could:

1. classify the request as one of: MQTT write, MQTT subscribe, OPC UA write, OPC UA subscribe, config export, content transfer
2. generate or reuse base entities (`DEVICE`, `PHYSICAL_DEVICE`, `PARAMETER_DEF`, `PARAMETER`)
3. generate the gateway-side request first
4. optionally generate the matching unsubscribe request
5. generate the Moqui-side wrapper request
6. insert seed SQL in transaction-safe order
7. optionally run a smoke call through `moqui.device.DeviceServices.run#DeviceRequest`

## Source References

Primary source patterns came from:

- `src/test/resources/device-gateway-seed.sql`
- `src/test/resources/device-gateway-opcua-seed.sql`
- `moqui-device/service/moqui/device/DeviceGatewayServices.xml`
- `moqui-device/entity/DeviceEntities.xml`
