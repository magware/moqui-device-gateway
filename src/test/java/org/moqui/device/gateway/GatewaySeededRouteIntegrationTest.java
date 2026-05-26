package org.moqui.device.gateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.camel.CamelContext;
import org.apache.camel.ConsumerTemplate;
import org.apache.camel.ProducerTemplate;
import org.awaitility.Awaitility;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.moqui.device.gateway.service.GatewayRequestService;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;

@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewaySeededRouteIntegrationTest {
    private static final String MQTT_BROKER = "tcp://localhost:1883";
    private static final String MQTT_USER = "artemis";
    private static final String MQTT_PASSWORD = "artemis";
    private static final String TEST_SUFFIX = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    // buildEndpointUri inserts the topic between prefix and '?', producing:
    //   paho-mqtt5:<topic>?brokerUrl=...&userName=...&password=...
    private static final String MQTT_URI_BASE =
        "paho-mqtt5:?brokerUrl=tcp://localhost:1883&userName=artemis&password=artemis";

    @Inject CamelContext camelContext;
    @Inject ProducerTemplate producer;
    @Inject ConsumerTemplate consumer;
    @Inject AgroalDataSource dataSource;
    @Inject GatewayRequestService gatewayRequestService;

    @BeforeEach
    void seedData() throws Exception {
        deleteSeedData();
        runSeedSql();
    }

    @AfterEach
    void cleanup() throws Exception {
        camelContext.getPropertiesComponent().setOverrideProperties(new Properties());
        while (consumer.receiveBody("seda:testConfigOut", 10L) != null) {}
        Files.deleteIfExists(Path.of("target/test-recipes/VirtualPlcRuleSet_p01.txt"));
        removeDynamicRoutes(seed("VPL_MQTT_SUB_REQ"));
        deleteSeedData();
    }

    @Test
    @Order(1)
    void mqttPublishOfficialUseCasePublishesVirtualPlcTopics() throws Exception {
        MqttClient subscriber = mqttClient("seeded-pub-sub-" + TEST_SUFFIX);
        try {
            LinkedBlockingQueue<MessageRecord> messages = new LinkedBlockingQueue<>();
            subscriber.setCallback(new QueueingMqttCallback(messages));
            subscriber.subscribe("virtual_plc_reference", 1);
            subscriber.subscribe("virtual_plc_maincontrolword", 1);

            @SuppressWarnings("unchecked")
            Map<String, Object> result = producer.requestBody(
                "direct:mqtt-write-device-request",
                gatewayRequestService.loadRequestContext(seed("VPL_MQTT_PUBLISH_REQ")),
                Map.class
            );

            assertEquals("completed", result.get("status"));
            assertEquals("mqtt-write-device-request", result.get("routeId"));

            MessageRecord first = messages.poll(5, TimeUnit.SECONDS);
            MessageRecord second = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(first, "Expected MQTT publish for virtual_plc_reference or virtual_plc_maincontrolword");
            assertNotNull(second, "Expected second MQTT publish for virtual_plc_reference or virtual_plc_maincontrolword");

            String combined = first.payload + "\n" + second.payload;
            String topics = first.topic + "\n" + second.topic;
            assertTrue(topics.contains("virtual_plc_reference"));
            assertTrue(topics.contains("virtual_plc_maincontrolword"));
            assertTrue(combined.contains(seed("VPL_PARAM_REFERENCE")));
            assertTrue(combined.contains(seed("VPL_PARAM_MAIN_CONTROL_WORD")));
            assertTrue(combined.contains("\"numericValue\":300.0") || combined.contains("\"numericValue\":300"));
            assertTrue(combined.contains("\"symbolicValue\":\"START\""));
        } finally {
            subscriber.disconnectForcibly(0, 0, false);
            subscriber.close();
        }
    }

    @Test
    @Order(2)
    void mqttSubscribeOfficialUseCaseIngestsVirtualPlcTopics() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> subscribeResult = producer.requestBody(
            "direct:mqtt-subscribe-device-request",
            gatewayRequestService.loadRequestContext(seed("VPL_MQTT_SUB_REQ")),
            Map.class
        );

        assertEquals("completed", subscribeResult.get("status"));
        assertEquals("mqtt-subscribe-device-request", subscribeResult.get("routeId"));

        MqttClient publisher = mqttClient("seeded-sub-pub-" + TEST_SUFFIX);
        try {
            publish(publisher, "virtual_plc_feedback", "{\"numericValue\":321.5}");
            publish(publisher, "virtual_plc_fault", "{\"symbolicValue\":\"Y\"}");
        } finally {
            publisher.disconnectForcibly(0, 0, false);
            publisher.close();
        }

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertEquals(321.5, fetchParameterNumericValue(seed("VPL_PARAM_FEEDBACK")), 0.001);
            assertEquals("Y", fetchParameterSymbolicValue(seed("VPL_PARAM_FAULT")));
        });

        Map<String, Object> unsubscribeResult = gatewayRequestService.unsubscribe(
            gatewayRequestService.loadRequestContext(seed("VPL_MQTT_UNSUB_REQ")));
        assertEquals("completed", unsubscribeResult.get("status"));
        assertEquals(seed("VPL_MQTT_SUB_REQ"), unsubscribeResult.get("targetRequestName"));
        assertTrue(((java.util.List<?>) unsubscribeResult.get("routeIdList")).size() >= 2);
    }

    @Test
    @Order(3)
    void exportRouteProducesSeededRecipeText() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = producer.requestBody("direct:run-device-config-export",
            Map.of("deviceRuleSetId", seed("VPL_RULESET_1")), Map.class);

        assertEquals("completed", result.get("status"));
        assertEquals("run-device-config-export", result.get("routeId"));

        // Response now carries a files[] array (one entry per priority group)
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> files = (java.util.List<Map<String, Object>>) result.get("files");
        assertNotNull(files, "Response must contain 'files' array");
        assertEquals(1, files.size(), "Seed data has one priority group (priority=1)");

        Map<String, Object> fileEntry = files.get(0);
        assertEquals("VirtualPlcRuleSet_p01.txt", fileEntry.get("filename"),
            "Filename must follow {ruleSetName}_p{priority:02d}.txt pattern");
        assertEquals(2, ((Number) fileEntry.get("rowCount")).intValue(),
            "Priority group must contain 2 parameters (RecipeReference + RecipeState)");
        assertEquals(1, ((Number) fileEntry.get("priority")).intValue());

        // Verify the actual file was written with correct content
        Path recipePath = Path.of("target/test-recipes/VirtualPlcRuleSet_p01.txt");
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> Files.exists(recipePath));
        String recipe = Files.readString(recipePath, StandardCharsets.UTF_8);
        // format: DeviceName.ParameterName:=value (multi-device-safe naming convention)
        assertTrue(recipe.contains("virtual_plc.RecipeReference:=250.0"),
            "Recipe must contain RecipeReference parameter");
        assertTrue(recipe.contains("virtual_plc.RecipeState:=AUTO"),
            "Recipe must contain RecipeState parameter");

        Files.deleteIfExists(recipePath);
    }

    private void runSeedSql() throws Exception {
        String sql = resourceText("device-gateway-seed.sql")
            .replace("__SUFFIX__", TEST_SUFFIX)
            .replace("__MQTT_PUBLISH_BASE_URI__", MQTT_URI_BASE)
            .replace("__MQTT_SUBSCRIBE_BASE_URI__", MQTT_URI_BASE);
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            for (String stmt : splitSqlStatements(sql)) {
                if (!stmt.isEmpty()) st.execute(stmt);
            }
        }
    }

    private java.util.List<String> splitSqlStatements(String sql) {
        java.util.List<String> statements = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '\'') {
                current.append(ch);
                if (inString && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    current.append(sql.charAt(++i));
                } else {
                    inString = !inString;
                }
            } else if (ch == ';' && !inString) {
                String trimmed = current.toString().trim();
                if (!trimmed.isEmpty()) statements.add(trimmed);
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        String trimmed = current.toString().trim();
        if (!trimmed.isEmpty()) statements.add(trimmed);
        return statements;
    }

    private void deleteSeedData() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            String suffixLike = "%_" + TEST_SUFFIX;
            st.executeUpdate("DELETE FROM PARAMETER_LOG WHERE PARAMETER_ID LIKE '" + suffixLike + "'");
            st.executeUpdate("DELETE FROM DEVICE_RULE WHERE DEVICE_RULE_ID LIKE '" + suffixLike + "'");
            st.executeUpdate("DELETE FROM DEVICE_RULE_SET WHERE DEVICE_RULE_SET_ID LIKE '" + suffixLike + "'");
            st.executeUpdate("DELETE FROM DEVICE_REQUEST_ITEM WHERE REQUEST_NAME LIKE '" + suffixLike + "'");
            st.executeUpdate("DELETE FROM DEVICE_REQUEST WHERE REQUEST_NAME LIKE '" + suffixLike + "'");
            st.executeUpdate("DELETE FROM PARAMETER WHERE PARAMETER_ID LIKE '" + suffixLike + "'");
            st.executeUpdate("DELETE FROM DEVICE_CONFIG WHERE DEVICE_CONFIG_ID LIKE '" + suffixLike + "'");
            st.executeUpdate("DELETE FROM PARAMETER_DEF WHERE PARAMETER_DEF_ID LIKE '" + suffixLike + "'");
            st.executeUpdate("DELETE FROM PHYSICAL_DEVICE WHERE DEVICE_ID LIKE '" + suffixLike + "'");
            st.executeUpdate("DELETE FROM DEVICE WHERE DEVICE_ID LIKE '" + suffixLike + "'");
        }
    }

    private void removeDynamicRoutes(String requestName) throws Exception {
        String routePrefix = "device-request-consumer-" + requestName + "-";
        for (String routeId : camelContext.getRoutes().stream().map(route -> route.getId()).toList()) {
            if (routeId.startsWith(routePrefix)) {
                camelContext.getRouteController().stopRoute(routeId);
                camelContext.removeRoute(routeId);
            }
        }
    }

    private double fetchParameterNumericValue(String parameterId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT NUMERIC_VALUE FROM PARAMETER WHERE PARAMETER_ID = ?")) {
            ps.setString(1, parameterId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getDouble(1);
            }
        }
    }

    private String fetchParameterSymbolicValue(String parameterId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT SYMBOLIC_VALUE FROM PARAMETER WHERE PARAMETER_ID = ?")) {
            ps.setString(1, parameterId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private MqttClient mqttClient(String clientId) throws Exception {
        MqttClient client = new MqttClient(MQTT_BROKER, clientId, null);
        MqttConnectionOptions opts = new MqttConnectionOptions();
        opts.setUserName(MQTT_USER);
        opts.setPassword(MQTT_PASSWORD.getBytes(StandardCharsets.UTF_8));
        opts.setCleanStart(true);
        opts.setConnectionTimeout(10);
        client.connect(opts);
        return client;
    }

    private void publish(MqttClient client, String topic, String payload) throws Exception {
        MqttMessage msg = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        msg.setQos(1);
        client.publish(topic, msg);
    }

    private String seed(String base) {
        return base + "_" + TEST_SUFFIX;
    }

    private String resourceText(String resourceName) throws IOException {
        try (var stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName)) {
            assertNotNull(stream, "Missing resource: " + resourceName);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record MessageRecord(String topic, String payload) {}

    private static final class QueueingMqttCallback implements MqttCallback {
        private final LinkedBlockingQueue<MessageRecord> messages;

        private QueueingMqttCallback(LinkedBlockingQueue<MessageRecord> messages) {
            this.messages = messages;
        }

        @Override
        public void disconnected(org.eclipse.paho.mqttv5.client.MqttDisconnectResponse disconnectResponse) {}

        @Override
        public void mqttErrorOccurred(org.eclipse.paho.mqttv5.common.MqttException exception) {}

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            messages.offer(new MessageRecord(topic, new String(message.getPayload(), StandardCharsets.UTF_8)));
        }

        @Override
        public void deliveryComplete(org.eclipse.paho.mqttv5.client.IMqttToken token) {}

        @Override
        public void connectComplete(boolean reconnect, String serverURI) {}

        @Override
        public void authPacketArrived(int reasonCode, MqttProperties properties) {}
    }
}
