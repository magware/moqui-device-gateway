package org.moqui.device.gateway.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Tracks persistent inbound storage failures (DB writes to PARAMETER / PARAMETER_LOG)
 * and notifies Moqui via a configurable REST callback when errors exceed the threshold.
 *
 * Called from the doCatch blocks of the store-device-request-inbound Camel route.
 * The consumer route (subscription or static) must set the exchange property
 * "gateway.routeId" so that errors are tracked per-subscription.
 */
@ApplicationScoped
@Named("inboundErrorNotifier")
public class InboundErrorNotifier {

    private static final Logger logger = Logger.getLogger(InboundErrorNotifier.class);

    @Inject
    ProducerTemplate producer;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "gateway.inbound.error.notification.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "gateway.inbound.error.notification.threshold.seconds", defaultValue = "60")
    int thresholdSeconds;

    @ConfigProperty(name = "gateway.inbound.error.notification.uri")
    Optional<String> notificationUri;

    private final ConcurrentHashMap<String, ErrorState> errors = new ConcurrentHashMap<>();

    /**
     * Called from the doCatch block in store-device-request-inbound.
     * Logs first occurrence; sends one notification to Moqui when the error
     * has persisted beyond gateway.inbound.error.notification.threshold.seconds.
     */
    public void recordError(Exchange exchange) {
        Exception caught = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        String message = caught != null ? caught.getMessage() : "unknown DB write failure";
        String routeId = resolveRouteId(exchange);

        ErrorState state = errors.computeIfAbsent(routeId, k -> {
            logger.warnf("Inbound error tracking started for route %s: %s", k, message);
            return new ErrorState(System.currentTimeMillis());
        });

        if (!enabled) return;

        long elapsed = (System.currentTimeMillis() - state.firstErrorTime) / 1000;
        if (elapsed >= thresholdSeconds && state.notified.compareAndSet(false, true)) {
            sendNotification(exchange, routeId, message, state.firstErrorTime, elapsed, "inboundError");
        }
    }

    /**
     * Called from the success path in store-device-request-inbound.
     * Clears the error state; sends a recovery notification to Moqui if a prior
     * error notification had been sent.
     */
    public void clearError(Exchange exchange) {
        String routeId = resolveRouteId(exchange);
        ErrorState prev = errors.remove(routeId);
        if (prev != null && prev.notified.get() && enabled) {
            long durationSeconds = (System.currentTimeMillis() - prev.firstErrorTime) / 1000;
            sendNotification(exchange, routeId, null, prev.firstErrorTime, durationSeconds, "inboundRecovered");
        }
    }

    private String resolveRouteId(Exchange exchange) {
        String prop = exchange.getProperty("gateway.routeId", String.class);
        return prop != null ? prop : exchange.getFromRouteId();
    }

    private void sendNotification(Exchange exchange, String routeId, String errorMessage,
                                   long firstErrorTime, long durationSeconds, String eventType) {
        String uri = notificationUri.orElse(null);
        if (uri == null || uri.isBlank()) {
            logger.warnf("Inbound %s on route %s — no notification URI configured", eventType, routeId);
            return;
        }

        String requestName = exchange.getProperty("gateway.requestName", String.class);
        String protocol = exchange.getProperty("protocolLabel", String.class);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType);
        payload.put("routeId", routeId);
        if (requestName != null) payload.put("requestName", requestName);
        if (protocol != null) payload.put("protocol", protocol);
        if (errorMessage != null) payload.put("errorMessage", errorMessage);
        payload.put("firstErrorTime", Instant.ofEpochMilli(firstErrorTime).toString());
        payload.put("errorDurationSeconds", durationSeconds);

        try {
            String body = objectMapper.writeValueAsString(payload);
            Thread.ofVirtual().name("inbound-notify-" + routeId).start(() -> {
                try {
                    producer.sendBody(uri, body);
                    logger.infof("Sent %s notification for route %s", eventType, routeId);
                } catch (Exception e) {
                    logger.warnf(e, "Failed to send %s notification for route %s", eventType, routeId);
                }
            });
        } catch (Exception e) {
            logger.warnf(e, "Failed to serialize %s notification for route %s", eventType, routeId);
        }
    }

    private static final class ErrorState {
        final long firstErrorTime;
        final AtomicBoolean notified = new AtomicBoolean(false);

        ErrorState(long firstErrorTime) {
            this.firstErrorTime = firstErrorTime;
        }
    }
}
