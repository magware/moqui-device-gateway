package org.moqui.device.gateway.health;

import java.util.ArrayList;
import java.util.List;

import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.ServiceStatus;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.moqui.device.gateway.service.SubscriptionPersistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Readiness check that verifies:
 * - Every persisted subscription has at least one running dynamic route.
 * - The static MQTT consumer (if configured on a real broker) is started.
 *
 * Reports UP when all expected routes are running, DOWN otherwise.
 */
@Readiness
@ApplicationScoped
public class GatewaySubscriptionHealthCheck implements HealthCheck {

    private static final String DYNAMIC_ROUTE_PREFIX = "device-request-consumer-";

    @Inject
    CamelContext camelContext;

    @Inject
    SubscriptionPersistence subscriptionPersistence;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("gateway-subscriptions");

        List<String> persisted = subscriptionPersistence.loadAll();
        List<String> stopped = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        int dynamicRunning = 0;

        for (Route route : camelContext.getRoutes()) {
            if (route.getId().startsWith(DYNAMIC_ROUTE_PREFIX)) {
                ServiceStatus status = camelContext.getRouteController().getRouteStatus(route.getId());
                if (status != null && status.isStarted()) {
                    dynamicRunning++;
                } else {
                    stopped.add(route.getId());
                }
            }
        }

        for (String requestName : persisted) {
            String prefix = DYNAMIC_ROUTE_PREFIX + requestName + "-";
            boolean found = camelContext.getRoutes().stream()
                .anyMatch(r -> r.getId().startsWith(prefix));
            if (!found) missing.add(requestName);
        }

        boolean staticConsumerHealthy = checkStaticConsumer(stopped);

        builder.withData("dynamicRoutesRunning", dynamicRunning)
            .withData("stoppedRoutes", stopped.isEmpty() ? "none" : String.join(", ", stopped))
            .withData("registeredSubscriptions", persisted.size())
            .withData("missingSubscriptions", missing.isEmpty() ? "none" : String.join(", ", missing));

        boolean healthy = stopped.isEmpty() && missing.isEmpty() && staticConsumerHealthy;
        return healthy ? builder.up().build() : builder.down().build();
    }

    private boolean checkStaticConsumer(List<String> stopped) {
        var staticRoute = camelContext.getRoute("mqtt-read-device-request-consumer");
        if (staticRoute == null) return true;
        if (staticRoute.getEndpoint().getEndpointUri().startsWith("seda:")) return true;

        ServiceStatus status = camelContext.getRouteController().getRouteStatus("mqtt-read-device-request-consumer");
        if (status != null && status.isStarted()) return true;

        stopped.add("mqtt-read-device-request-consumer");
        return false;
    }
}
