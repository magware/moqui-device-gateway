package org.moqui.device.gateway;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.camel.CamelContext;
import org.apache.camel.ConsumerTemplate;
import org.apache.camel.Exchange;
import org.junit.jupiter.api.Test;
import org.moqui.device.gateway.service.InboundErrorNotifier;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;

/**
 * Unit-level tests for {@link InboundErrorNotifier}.
 *
 * Uses threshold=0 (see InboundErrorNotifierTestProfile) so that the very first
 * recordError() call fires a notification without waiting. Notifications are captured
 * from a SEDA endpoint rather than an external HTTP endpoint.
 *
 * Does NOT require an MQTT broker or PostgreSQL — the test profile uses SEDA
 * endpoints and in-memory H2 only.
 */
@QuarkusTest
@TestProfile(InboundErrorNotifierTestProfile.class)
class InboundErrorNotifierTest {

    private static final String NOTIFICATION_SEDA = InboundErrorNotifierTestProfile.NOTIFICATION_SEDA;
    private static final long   RECEIVE_TIMEOUT_MS = 5_000L;

    @Inject
    InboundErrorNotifier notifier;

    @Inject
    CamelContext camelContext;

    @Inject
    ConsumerTemplate consumer;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a minimal Exchange with the given routeId, protocolLabel, and a
     * synthetic exception as the EXCEPTION_CAUGHT property — the same fields that
     * store-device-request-inbound sets before calling recordError().
     */
    private Exchange mockExchange(String routeId, String protocol, String errorMsg) {
        Exchange ex = camelContext.getEndpoint("direct:test-notifier").createExchange();
        ex.setProperty("gateway.routeId",    routeId);
        ex.setProperty("protocolLabel",      protocol);
        ex.setProperty(Exchange.EXCEPTION_CAUGHT, new RuntimeException(errorMsg));
        return ex;
    }

    private Exchange mockExchange(String routeId) {
        return mockExchange(routeId, "MQTT", "DB connection refused");
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * A recordError() call fires an inboundError notification immediately when
     * threshold.seconds=0.
     * The notification body must contain eventType, routeId, protocol, errorMessage.
     */
    @Test
    void errorNotificationSentOnFirstRecordError() {
        String routeId = "test-route-err-" + java.util.UUID.randomUUID().toString().substring(0, 6);
        Exchange ex = mockExchange(routeId, "OPC UA", "Connection refused");

        notifier.recordError(ex);

        String body = consumer.receiveBody(NOTIFICATION_SEDA, RECEIVE_TIMEOUT_MS, String.class);
        assertNotNull(body, "Expected an inboundError notification on SEDA endpoint");
        assertTrue(body.contains("\"eventType\":\"inboundError\""),
            "Payload must contain eventType=inboundError. Got: " + body);
        assertTrue(body.contains("\"routeId\":\"" + routeId + "\""),
            "Payload must contain the routeId. Got: " + body);
        assertTrue(body.contains("\"protocol\":\"OPC UA\""),
            "Payload must contain the protocol. Got: " + body);
        assertTrue(body.contains("\"errorMessage\":\"Connection refused\""),
            "Payload must contain the errorMessage. Got: " + body);
        assertTrue(body.contains("\"firstErrorTime\""),
            "Payload must contain firstErrorTime. Got: " + body);
        assertTrue(body.contains("\"errorDurationSeconds\""),
            "Payload must contain errorDurationSeconds. Got: " + body);
    }

    /**
     * After a prior error, clearError() sends an inboundRecovered notification.
     */
    @Test
    void recoveryNotificationSentAfterClearError() throws InterruptedException {
        String routeId = "test-route-rec-" + java.util.UUID.randomUUID().toString().substring(0, 6);
        Exchange errorEx    = mockExchange(routeId, "MQTT", "DB timeout");
        Exchange clearEx    = camelContext.getEndpoint("direct:test-notifier").createExchange();
        clearEx.setProperty("gateway.routeId", routeId);

        notifier.recordError(errorEx);
        // Drain the inboundError notification
        consumer.receiveBody(NOTIFICATION_SEDA, RECEIVE_TIMEOUT_MS, String.class);

        notifier.clearError(clearEx);

        String recovery = consumer.receiveBody(NOTIFICATION_SEDA, RECEIVE_TIMEOUT_MS, String.class);
        assertNotNull(recovery, "Expected an inboundRecovered notification after clearError()");
        assertTrue(recovery.contains("\"eventType\":\"inboundRecovered\""),
            "Recovery payload must contain eventType=inboundRecovered. Got: " + recovery);
        assertTrue(recovery.contains("\"routeId\":\"" + routeId + "\""),
            "Recovery payload must carry the same routeId. Got: " + recovery);
    }

    /**
     * Only one notification is sent per route even when recordError() is called
     * multiple times for the same routeId (notified flag prevents duplicates).
     */
    @Test
    void noRepeatNotificationForSameRoute() {
        String routeId = "test-route-dedup-" + java.util.UUID.randomUUID().toString().substring(0, 6);
        Exchange ex = mockExchange(routeId);

        notifier.recordError(ex); // fires
        notifier.recordError(ex); // must NOT fire again (notified=true)

        String first  = consumer.receiveBody(NOTIFICATION_SEDA, RECEIVE_TIMEOUT_MS, String.class);
        assertNotNull(first, "First notification must be received");

        // Give virtual thread ample time; second message must NOT arrive
        String second = consumer.receiveBody(NOTIFICATION_SEDA, 800L, String.class);
        assertNull(second,
            "No second notification must be sent when the notified flag is already set");
    }

    /**
     * clearError() on a route that never had an error is a no-op
     * (no notification, no exception).
     */
    @Test
    void clearErrorOnCleanRouteIsNoop() {
        String routeId = "test-route-noop-" + java.util.UUID.randomUUID().toString().substring(0, 6);
        Exchange ex = camelContext.getEndpoint("direct:test-notifier").createExchange();
        ex.setProperty("gateway.routeId", routeId);

        notifier.clearError(ex); // must not throw and must not send any notification

        String msg = consumer.receiveBody(NOTIFICATION_SEDA, 500L, String.class);
        assertNull(msg, "clearError on a clean route must not produce any notification");
    }

    /**
     * Independent routes track errors independently: an error on route A does not
     * interfere with route B's state.
     */
    @Test
    void errorStatesAreTrackedPerRoute() {
        String routeA = "test-route-ind-A-" + java.util.UUID.randomUUID().toString().substring(0, 6);
        String routeB = "test-route-ind-B-" + java.util.UUID.randomUUID().toString().substring(0, 6);

        notifier.recordError(mockExchange(routeA, "MQTT", "error on A"));
        notifier.recordError(mockExchange(routeB, "MQTT", "error on B"));

        // Both routes must produce a notification
        String notifA = consumer.receiveBody(NOTIFICATION_SEDA, RECEIVE_TIMEOUT_MS, String.class);
        String notifB = consumer.receiveBody(NOTIFICATION_SEDA, RECEIVE_TIMEOUT_MS, String.class);
        assertNotNull(notifA, "Route A must produce a notification");
        assertNotNull(notifB, "Route B must produce a notification");

        // The two notifications must reference different routeIds
        String combined = notifA + "\n" + notifB;
        assertTrue(combined.contains(routeA), "Notification for route A must contain its routeId");
        assertTrue(combined.contains(routeB), "Notification for route B must contain its routeId");
    }
}
