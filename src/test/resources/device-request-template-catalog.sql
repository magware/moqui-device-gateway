-- Device Request Template Catalog
-- Purpose: reusable SQL skeletons for future automation / skill-based seed generation.
-- Replace __PLACEHOLDERS__ before executing.

-- ============================================================
-- Shared Base Entities
-- ============================================================

INSERT INTO DEVICE (DEVICE_ID, DEVICE_TYPE_ENUM_ID)
VALUES ('__DEVICE_ID__', '__DEVICE_TYPE_ENUM_ID__');

INSERT INTO PHYSICAL_DEVICE (DEVICE_ID, DEVICE_NAME)
VALUES ('__DEVICE_ID__', '__DEVICE_NAME__');

INSERT INTO PARAMETER_DEF (PARAMETER_DEF_ID, PARAMETER_TYPE_ENUM_ID, PARAMETER_CODE, PARAMETER_NAME)
VALUES ('__PARAMETER_DEF_ID__', '__PARAMETER_TYPE_ENUM_ID__', '__PARAMETER_CODE__', '__PARAMETER_NAME__');

-- choose the right value column for the parameter type
INSERT INTO PARAMETER (PARAMETER_ID, PARAMETER_DEF_ID, DEVICE_ID, NUMERIC_VALUE)
VALUES ('__PARAMETER_ID__', '__PARAMETER_DEF_ID__', '__DEVICE_ID__', 0.0);

-- ============================================================
-- Gateway-Side MQTT Write
-- ============================================================

INSERT INTO DEVICE_REQUEST (
    REQUEST_NAME, DEVICE_ID, REQUEST_TYPE_ENUM_ID, PURPOSE_ENUM_ID,
    ROUTER_ENUM_ID, BROKER_URI, ONLY_CHANGED_PARAMETERS
) VALUES (
    '__GW_MQTT_WRITE_REQ__', '__DEVICE_ID__', 'DrtWrite', '__PURPOSE_ENUM_ID__',
    'DrrBroker', '__MQTT_PUBLISH_BASE_URI__', 'N'
);

INSERT INTO DEVICE_REQUEST_ITEM (REQUEST_NAME, PARAMETER_ID, SEQUENCE_NUM, QUERY)
VALUES ('__GW_MQTT_WRITE_REQ__', '__PARAMETER_ID_1__', 1, '__TOPIC_1__');

-- ============================================================
-- Gateway-Side MQTT Subscribe / Unsubscribe
-- ============================================================

INSERT INTO DEVICE_REQUEST (
    REQUEST_NAME, DEVICE_ID, REQUEST_TYPE_ENUM_ID, PURPOSE_ENUM_ID,
    ROUTER_ENUM_ID, BROKER_URI, POLLING_INTERVAL
) VALUES (
    '__GW_MQTT_SUB_REQ__', '__DEVICE_ID__', 'DrtCyclic', '__PURPOSE_ENUM_ID__',
    'DrrBroker', '__MQTT_SUBSCRIBE_BASE_URI__', 100
);

INSERT INTO DEVICE_REQUEST_ITEM (REQUEST_NAME, PARAMETER_ID, SEQUENCE_NUM, QUERY)
VALUES ('__GW_MQTT_SUB_REQ__', '__PARAMETER_ID_1__', 1, '__TOPIC_1__');

INSERT INTO DEVICE_REQUEST (
    REQUEST_NAME, PARENT_REQUEST_NAME, DEVICE_ID, REQUEST_TYPE_ENUM_ID,
    PURPOSE_ENUM_ID, ROUTER_ENUM_ID, BROKER_URI
) VALUES (
    '__GW_MQTT_UNSUB_REQ__', '__GW_MQTT_SUB_REQ__', '__DEVICE_ID__', 'DrtUnsubscribe',
    '__PURPOSE_ENUM_ID__', 'DrrBroker', '__MQTT_SUBSCRIBE_BASE_URI__'
);

-- ============================================================
-- Gateway-Side OPC UA Subscribe / Read / Unsubscribe
-- ============================================================

INSERT INTO DEVICE_REQUEST (
    REQUEST_NAME, DEVICE_ID, REQUEST_TYPE_ENUM_ID, PURPOSE_ENUM_ID,
    ROUTER_ENUM_ID, CONNECTION_NAME, POLLING_INTERVAL
) VALUES (
    '__GW_OPCUA_SUB_REQ__', '__DEVICE_ID__', 'DrtCyclic', '__PURPOSE_ENUM_ID__',
    'DrrDirect', '__OPCUA_CONNECTION_NAME__', 100
);

INSERT INTO DEVICE_REQUEST_ITEM (REQUEST_NAME, PARAMETER_ID, SEQUENCE_NUM, QUERY)
VALUES ('__GW_OPCUA_SUB_REQ__', '__PARAMETER_ID_1__', 1, '__OPCUA_NODE_1__');

INSERT INTO DEVICE_REQUEST (
    REQUEST_NAME, PARENT_REQUEST_NAME, DEVICE_ID, REQUEST_TYPE_ENUM_ID,
    PURPOSE_ENUM_ID, ROUTER_ENUM_ID, CONNECTION_NAME
) VALUES (
    '__GW_OPCUA_UNSUB_REQ__', '__GW_OPCUA_SUB_REQ__', '__DEVICE_ID__', 'DrtUnsubscribe',
    '__PURPOSE_ENUM_ID__', 'DrrDirect', '__OPCUA_CONNECTION_NAME__'
);

-- one-shot OPC UA read variant
INSERT INTO DEVICE_REQUEST (
    REQUEST_NAME, DEVICE_ID, REQUEST_TYPE_ENUM_ID, PURPOSE_ENUM_ID,
    ROUTER_ENUM_ID, CONNECTION_NAME
) VALUES (
    '__GW_OPCUA_READ_REQ__', '__DEVICE_ID__', 'DrtRead', '__PURPOSE_ENUM_ID__',
    'DrrDirect', '__OPCUA_CONNECTION_NAME__'
);

INSERT INTO DEVICE_REQUEST_ITEM (REQUEST_NAME, PARAMETER_ID, SEQUENCE_NUM, QUERY)
VALUES ('__GW_OPCUA_READ_REQ__', '__PARAMETER_ID_1__', 1, '__OPCUA_NODE_1__');

-- ============================================================
-- Gateway-Side OPC UA Write
-- ============================================================

INSERT INTO DEVICE_REQUEST (
    REQUEST_NAME, DEVICE_ID, REQUEST_TYPE_ENUM_ID, PURPOSE_ENUM_ID,
    ROUTER_ENUM_ID, CONNECTION_NAME, ONLY_CHANGED_PARAMETERS
) VALUES (
    '__GW_OPCUA_WRITE_REQ__', '__DEVICE_ID__', 'DrtWrite', '__PURPOSE_ENUM_ID__',
    'DrrDirect', '__OPCUA_CONNECTION_NAME__', 'N'
);

INSERT INTO DEVICE_REQUEST_ITEM (REQUEST_NAME, PARAMETER_ID, SEQUENCE_NUM, QUERY)
VALUES ('__GW_OPCUA_WRITE_REQ__', '__PARAMETER_ID_1__', 1, '__OPCUA_WRITE_NODE_1__');

-- ============================================================
-- Gateway-Side Content Transfer
-- ============================================================

INSERT INTO DEVICE_REQUEST (
    REQUEST_NAME, DEVICE_ID, REQUEST_TYPE_ENUM_ID, PURPOSE_ENUM_ID,
    ROUTER_ENUM_ID, BROKER_URI
) VALUES (
    '__GW_TRANSFER_REQ__', '__DEVICE_ID__', 'DrtContentTransfer', '__PURPOSE_ENUM_ID__',
    'DrrBroker', '__TRANSFER_URI__'
);

-- ============================================================
-- Moqui-Side Gateway Wrapper
-- ============================================================

INSERT INTO DEVICE (DEVICE_ID, DEVICE_TYPE_ENUM_ID)
VALUES ('__GATEWAY_DEVICE_ID__', 'DtIoTGateway');

INSERT INTO PHYSICAL_DEVICE (DEVICE_ID, DEVICE_NAME)
VALUES ('__GATEWAY_DEVICE_ID__', '__GATEWAY_DEVICE_NAME__');

INSERT INTO DEVICE_REQUEST (
    REQUEST_NAME, DEVICE_ID, ROUTER_ENUM_ID, REQUEST_TYPE_ENUM_ID,
    RUN_SERVICE_NAME, BROKER_URI, QUERY, TIMEOUT, ONLY_CHANGED_PARAMETERS
) VALUES (
    '__MOQUI_WRAPPER_REQ__', '__GATEWAY_DEVICE_ID__', 'DrrMoquiDeviceGateway', '__REQUEST_TYPE_ENUM_ID__',
    'moqui.device.DeviceGatewayServices.run#GatewayDeviceRequest',
    '__GATEWAY_BASE_URL__',
    '__GW_TARGET_REQUEST_NAME__',
    30,
    'N'
);

-- run#DeviceRequest expects at least one item
INSERT INTO DEVICE_REQUEST_ITEM (REQUEST_NAME, PARAMETER_ID, SEQUENCE_NUM, QUERY)
VALUES ('__MOQUI_WRAPPER_REQ__', '__ANY_PARAMETER_ID_ON_TARGET_DEVICE__', 1, '__OPTIONAL_QUERY_HINT__');

-- ============================================================
-- Moqui-Side Config Export Wrapper
-- ============================================================

INSERT INTO DEVICE_REQUEST (
    REQUEST_NAME, DEVICE_ID, ROUTER_ENUM_ID, REQUEST_TYPE_ENUM_ID,
    RUN_SERVICE_NAME, BROKER_URI, TIMEOUT
) VALUES (
    '__MOQUI_EXPORT_REQ__', '__GATEWAY_DEVICE_ID__', 'DrrMoquiDeviceGateway', 'DrtConfigWrite',
    'moqui.device.DeviceGatewayServices.export#DeviceConfig',
    '__GATEWAY_BASE_URL__',
    30
);

-- ============================================================
-- Moqui-Side Content Transfer Wrapper
-- ============================================================

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
