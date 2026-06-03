package org.moqui.device.gateway;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;

@QuarkusTest
@TestProfile(GatewayRequestScopeValidationTestProfile.class)
class GatewayRequestScopeValidationTest {

    @Inject
    AgroalDataSource dataSource;

    @BeforeEach
    void resetSchemaAndData() throws Exception {
        runInitSql();
        deleteAllRows();
    }

    @Test
    void runDeviceRequestAllowsInScopeDeviceRequest() throws Exception {
        seedGatewayGraph();
        insertDeviceRequest("REQ_IN_SCOPE", "PLC_LINEA_01", "DrtUnsubscribe", "DrrMoquiDeviceGateway", nowMinusHours(1), null);

        given()
            .when()
            .post("/api/device-request/run/REQ_IN_SCOPE")
            .then()
            .statusCode(200)
            .body("status", equalTo("completed"));
    }

    @Test
    void runDeviceRequestRejectsOutOfScopeDeviceRequest() throws Exception {
        seedGatewayGraph();
        insertDeviceRequest("REQ_OUT_SCOPE", "PLC_LINEA_99", "DrtUnsubscribe", "DrrMoquiDeviceGateway", nowMinusHours(1), null);

        given()
            .when()
            .post("/api/device-request/run/REQ_OUT_SCOPE")
            .then()
            .statusCode(403)
            .body("error", equalTo("forbidden"))
            .body("message", containsString("not in the scope of gateway"));
    }

    private void seedGatewayGraph() throws Exception {
        insertDevice("GW_EDGE_01", "DtComputer");
        insertPhysicalDevice("GW_EDGE_01", "Gateway Edge 01");
        insertDevice("PLC_LINEA_01", "DtController");
        insertPhysicalDevice("PLC_LINEA_01", "PLC Linea 01");
        insertDevice("PLC_LINEA_99", "DtController");
        insertPhysicalDevice("PLC_LINEA_99", "PLC Linea 99");
        insertGroup("DG_LINEA_01_EDGE", "Linea 01 Edge Gateway Group");
        insertGroup("DG_LINEA_99_EDGE", "Linea 99 Edge Gateway Group");
        insertGroupMember("DG_LINEA_01_EDGE", "GW_EDGE_01", "DgmpEdgeGateway", 1, "DgmsAlive");
        insertGroupMember("DG_LINEA_01_EDGE", "PLC_LINEA_01", "DgmpProcessPLC", 2, "DgmsAlive");
        insertGroupMember("DG_LINEA_99_EDGE", "PLC_LINEA_99", "DgmpProcessPLC", 1, "DgmsAlive");
    }

    private void runInitSql() throws Exception {
        String sql = resourceText("db/init.sql");
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            for (String stmt : splitSqlStatements(sql)) {
                if (!stmt.isBlank()) st.execute(stmt);
            }
        }
    }

    private void deleteAllRows() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM DEVICE_RULE");
            st.executeUpdate("DELETE FROM DEVICE_RULE_SET");
            st.executeUpdate("DELETE FROM DEVICE_REQUEST_ITEM");
            st.executeUpdate("DELETE FROM DEVICE_REQUEST");
            st.executeUpdate("DELETE FROM DEVICE_CONNECTION");
            st.executeUpdate("DELETE FROM DEVICE_GROUP_MEMBER");
            st.executeUpdate("DELETE FROM DEVICE_GROUP");
            st.executeUpdate("DELETE FROM PARAMETER_LOG");
            st.executeUpdate("DELETE FROM PARAMETER");
            st.executeUpdate("DELETE FROM PARAMETER_DEF");
            st.executeUpdate("DELETE FROM PHYSICAL_DEVICE");
            st.executeUpdate("DELETE FROM DEVICE");
        }
    }

    private void insertDevice(String deviceId, String deviceTypeEnumId) throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO DEVICE (DEVICE_ID, DEVICE_TYPE_ENUM_ID) VALUES ('" + deviceId + "', '" + deviceTypeEnumId + "')");
        }
    }

    private void insertPhysicalDevice(String deviceId, String deviceName) throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO PHYSICAL_DEVICE (DEVICE_ID, DEVICE_NAME) VALUES ('" + deviceId + "', '" + deviceName + "')");
        }
    }

    private void insertGroup(String groupId, String groupName) throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO DEVICE_GROUP (DEVICE_ID, GROUP_NAME) VALUES ('" + groupId + "', '" + groupName + "')");
        }
    }

    private void insertGroupMember(String groupId, String memberDeviceId, String purposeEnumId, int sequenceNum, String statusId) throws Exception {
        String statusSql = statusId == null ? "NULL" : "'" + statusId + "'";
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO DEVICE_GROUP_MEMBER (DEVICE_ID, MEMBER_DEVICE_ID, PURPOSE_ENUM_ID, SEQUENCE_NUM, STATUS_ID) VALUES ('"
                + groupId + "', '" + memberDeviceId + "', '" + purposeEnumId + "', " + sequenceNum + ", " + statusSql + ")");
        }
    }

    private void insertDeviceRequest(String requestName, String deviceId, String requestType, String routerEnumId,
                                     Timestamp fromDate, Timestamp thruDate) throws Exception {
        String fromSql = fromDate == null ? "NULL" : "'" + fromDate + "'";
        String thruSql = thruDate == null ? "NULL" : "'" + thruDate + "'";
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO DEVICE_REQUEST (REQUEST_NAME, DEVICE_ID, REQUEST_TYPE_ENUM_ID, PURPOSE_ENUM_ID, ROUTER_ENUM_ID, FROM_DATE, THRU_DATE) VALUES ('"
                + requestName + "', '" + deviceId + "', '" + requestType + "', 'DrpMonitoring', '" + routerEnumId + "', "
                + fromSql + ", " + thruSql + ")");
        }
    }

    private List<String> splitSqlStatements(String sql) {
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

    private String resourceText(String resourceName) throws IOException {
        try (var stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Timestamp nowMinusHours(int hours) {
        return Timestamp.from(Instant.now().minusSeconds(hours * 3600L));
    }
}
