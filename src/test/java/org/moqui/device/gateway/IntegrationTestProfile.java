package org.moqui.device.gateway;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Quarkus test profile that activates the %integration section in application.properties.
 *
 * Requires live infrastructure:
 *   - PostgreSQL  localhost:5434  (docker/postgres-compose.yml)
 *   - ActiveMQ Artemis  localhost:1883  (activemq-compose.yml)
 *   - Moqui schema already created (gradlew load, then stop)
 */
public class IntegrationTestProfile implements QuarkusTestProfile {

    @Override
    public String getConfigProfile() {
        return "integration";
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
            "plc.log.route.autoStartup", "false",
            "quarkus.datasource.jdbc.url", "jdbc:postgresql://localhost:5434/moqui",
            "quarkus.datasource.log.jdbc.url", "jdbc:postgresql://localhost:5434/moqui"
        );
    }
}
