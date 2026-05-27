package org.moqui.device.gateway;

import java.util.HashMap;
import java.util.Map;

/**
 * Test profile for SubscriptionPersistenceTest.
 *
 * Redirects the subscription registry file to a test-specific path under target/
 * so the test never touches the production data/subscriptions.json.
 * MQTT routes are disabled — no broker is required.
 *
 * Prerequisite: PostgreSQL on localhost:5434 (Quarkus datasource initialization).
 * Run: mvn test -Dquarkus.profile=integration -Dtest=SubscriptionPersistenceTest
 */
public class SubscriptionPersistenceTestProfile extends IntegrationTestProfile {

    static final String REGISTRY_PATH = "target/test-subscriptions/subscriptions.json";

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("gateway.subscription.registry.path", REGISTRY_PATH);
        overrides.put("mqtt.read.route.autoStartup", "false");
        overrides.put("mqtt.read.consume.uri",       "seda:mqtt-read-device-request-in");
        overrides.put("plc.log.route.autoStartup",   "false");
        return overrides;
    }
}
