package org.moqui.device.gateway;

import java.util.HashMap;
import java.util.Map;

/**
 * Test profile for InboundErrorNotifierTest.
 *
 * Sets gateway.inbound.error.notification.threshold.seconds=0 so the very first
 * recordError() call triggers a notification without waiting 60 s.
 * Redirects notifications to a local SEDA endpoint so the test can consume them.
 * MQTT routes are disabled and both datasources are in-memory H2, so no
 * external broker or database is required.
 */
public class InboundErrorNotifierTestProfile extends LocalNoInfraTestProfile {

    static final String NOTIFICATION_SEDA = "seda:test-error-notifications";

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("gateway.inbound.error.notification.enabled",           "true");
        overrides.put("gateway.inbound.error.notification.threshold.seconds", "0");
        overrides.put("gateway.inbound.error.notification.uri",               NOTIFICATION_SEDA);
        // Disable MQTT routes — this test does not need a broker
        overrides.put("mqtt.read.route.autoStartup", "false");
        overrides.put("mqtt.read.consume.uri",       "seda:mqtt-read-device-request-in");
        overrides.put("plc.log.route.autoStartup",   "false");
        return overrides;
    }
}
