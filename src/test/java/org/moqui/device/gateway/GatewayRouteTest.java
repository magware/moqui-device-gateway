package org.moqui.device.gateway;

import java.util.Properties;

import org.apache.camel.CamelContext;
import org.apache.camel.ConsumerTemplate;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.ServiceStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

    /**
     * Unit tests for the gateway Java DSL Camel routes.
     *
     * Uses SEDA endpoints (default config) to avoid requiring a live database or broker.
     */
@QuarkusTest
class GatewayRouteTest {

    @Inject
    CamelContext camelContext;

    @Inject
    ProducerTemplate producer;

    @Inject
    ConsumerTemplate consumer;

    @Test
    void allRoutesAreLoaded() {
        assertNotNull(camelContext.getRoute("dispatch-device-request"),          "dispatch-device-request not loaded");
        assertNotNull(camelContext.getRoute("mqtt-read-device-request-consumer"),  "mqtt-read-device-request-consumer not loaded");
        assertNotNull(camelContext.getRoute("mqtt-read-device-request"),           "mqtt-read-device-request not loaded");
        assertNotNull(camelContext.getRoute("opcua-read-device-request-consumer"), "opcua-read-device-request-consumer not loaded");
        assertNotNull(camelContext.getRoute("opcua-read-device-request"),          "opcua-read-device-request not loaded");
        assertNotNull(camelContext.getRoute("store-device-request-inbound"),       "store-device-request-inbound not loaded");
        assertNotNull(camelContext.getRoute("run-device-config-export"),           "run-device-config-export not loaded");
        assertNotNull(camelContext.getRoute("transfer-file"),                      "transfer-file not loaded");
        assertNotNull(camelContext.getRoute("transfer-device-content"),            "transfer-device-content not loaded");
        assertNotNull(camelContext.getRoute("plc-log-consumer"),                   "plc-log-consumer not loaded");
        assertNotNull(camelContext.getRoute("plc-log-ingest"),                     "plc-log-ingest not loaded");
    }

    /**
     * Verifies that the secondary file-transfer step is triggered when
     * {@code file.transfer.uri.2.enabled=true} is set at runtime.
     *
     * Simulates the redundant PLC delivery scenario: the same recipe body
     * must reach both {@code file.transfer.uri} and {@code file.transfer.uri.2}.
     */
    @Test
    void fileTransferRouteDeliversToSecondaryWhenEnabled() throws Exception {
        // Activate secondary transfer by injecting override properties at runtime.
        // setOverrideProperties replaces all overrides, so we restore with empty after the test.
        Properties overrides = new Properties();
        overrides.setProperty("file.transfer.uri.2.enabled", "true");
        overrides.setProperty("file.transfer.uri.2", "seda:export-device-config-out-2?waitForTaskToComplete=Never");
        camelContext.getPropertiesComponent().setOverrideProperties(overrides);

        try {
            producer.sendBodyAndHeader("direct:transfer-file",
                "Speed:=300.0\nTemperature:=25.5",
                "CamelFileName", "recipe.txt");

            String primary   = consumer.receiveBody("seda:export-device-config-out",  3000L, String.class);
            String secondary = consumer.receiveBody("seda:export-device-config-out-2", 3000L, String.class);

            assertNotNull(primary,   "Primary PLC did not receive the recipe");
            assertNotNull(secondary, "Secondary PLC did not receive the recipe");
            assertEquals(primary, secondary, "Both PLCs must receive identical recipe content");
        } finally {
            camelContext.getPropertiesComponent().setOverrideProperties(new Properties());
            while (consumer.receiveBody("seda:export-device-config-out-2", 10L) != null) {}
        }
    }

    @Test
    void malformedInboundPayloadIsDiscardedWithoutStoppingConsumerRoute() {
        producer.sendBody("seda:mqtt-read-device-request-in", "{not-json");

        assertEquals(ServiceStatus.Started, camelContext.getRouteController().getRouteStatus("mqtt-read-device-request-consumer"));
        assertEquals(ServiceStatus.Started, camelContext.getRouteController().getRouteStatus("mqtt-read-device-request"));
    }
}
