package org.moqui.device.gateway.health;

import java.sql.Connection;
import java.util.Optional;

import io.agroal.api.AgroalDataSource;
import org.apache.camel.CamelContext;
import org.apache.camel.ServiceStatus;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.moqui.device.gateway.service.GatewayRequestService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Readiness verifies gateway identity and core process viability.
 * It must not go DOWN for temporary broker / PLC field conditions.
 */
@Readiness
@ApplicationScoped
public class GatewaySubscriptionHealthCheck implements HealthCheck {

    @Inject
    CamelContext camelContext;

    @Inject
    GatewayRequestService gatewayRequestService;

    @Inject
    AgroalDataSource dataSource;

    @ConfigProperty(name = "gateway.device.id")
    Optional<String> gatewayDeviceId;


    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("gateway-subscriptions");
        boolean dataSourceUp = false;
        try (Connection ignored = dataSource.getConnection()) {
            dataSourceUp = true;
        } catch (Exception e) {
            builder.withData("datasource", "DOWN");
            builder.withData("reason", e.getMessage());
            return builder.down().build();
        }

        ServiceStatus camelStatus = camelContext != null ? camelContext.getStatus() : null;
        GatewayRequestService.GatewayIdentityStatus identity;
        try {
            identity = gatewayRequestService.inspectGatewayIdentity();
        } catch (RuntimeException e) {
            builder.withData("gatewayDeviceId", configuredGatewayDeviceId().isBlank() ? "missing" : configuredGatewayDeviceId())
                .withData("datasource", dataSourceUp ? "UP" : "DOWN")
                .withData("camelStatus", camelStatus == null ? "null" : camelStatus.name())
                .withData("reason", e.getMessage());
            return builder.down().build();
        }

        builder.withData("gatewayDeviceId", configuredGatewayDeviceId().isBlank() ? "missing" : configuredGatewayDeviceId())
            .withData("datasource", dataSourceUp ? "UP" : "DOWN")
            .withData("camelStatus", camelStatus == null ? "null" : camelStatus.name())
            .withData("gatewayConfigured", identity.configured())
            .withData("physicalDeviceExists", identity.physicalDeviceExists())
            .withData("gatewayMembershipValid", identity.gatewayMemberFound())
            .withData("message", identity.message());

        boolean camelReady = camelStatus != null && camelStatus.isStarted();
        boolean ready = dataSourceUp && camelReady && identity.ready();
        return ready ? builder.up().build() : builder.down().build();
    }

    private String configuredGatewayDeviceId() {
        return gatewayDeviceId.orElse("");
    }
}
