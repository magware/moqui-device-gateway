package org.moqui.device.gateway;

import static org.hamcrest.Matchers.equalTo;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import static io.restassured.RestAssured.given;

@QuarkusTest
@TestProfile(LocalNoInfraTestProfile.class)
class GatewayResourceSecurityTest {

    @Test
    void apiRejectsAnonymousRequests() {
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/api/device-config/export")
        .then()
            .statusCode(401)
            .body("error", equalTo("unauthorized"));
    }

    @Test
    void apiAcceptsConfiguredApiKeyAndReturnsApplicationError() {
        given()
            .header("X-API-Key", "change-me-in-production")
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/api/device-config/export")
        .then()
            .statusCode(400)
            .body("error", equalTo("deviceRuleSetId is required"));
    }
}
