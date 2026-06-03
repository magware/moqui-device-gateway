package org.moqui.device.gateway.security;

import java.util.Optional;
import java.util.Set;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@ApplicationScoped
public class GatewaySecurityConfigValidator {

    private static final Set<String> SAFE_DEV_PROFILES = Set.of("dev", "test", "local", "integration");
    private static final Set<String> UNSAFE_DEFAULT_TOKENS = Set.of("change-me", "change-me-in-production");

    @ConfigProperty(name = "gateway.api.auth.enabled", defaultValue = "true")
    boolean authEnabled;

    @ConfigProperty(name = "gateway.api.auth.token", defaultValue = "change-me-in-production")
    String apiToken;

    @ConfigProperty(name = "quarkus.profile")
    Optional<String> quarkusProfile;

    void validateApiToken(@Observes StartupEvent event) {
        if (!authEnabled) return;

        String profile = quarkusProfile.orElse("prod");
        if (SAFE_DEV_PROFILES.contains(profile)) return;

        if (apiToken == null || apiToken.isBlank() || UNSAFE_DEFAULT_TOKENS.contains(apiToken)) {
            throw new IllegalStateException(
                "Unsafe gateway API token. Set GATEWAY_API_AUTH_TOKEN to a real secret."
            );
        }
    }
}
