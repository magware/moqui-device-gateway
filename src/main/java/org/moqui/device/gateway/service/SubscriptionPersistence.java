package org.moqui.device.gateway.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Persists the set of active subscription request names to a JSON file so that
 * subscriptions survive process restarts.
 *
 * Writes are atomic: payload is written to a .tmp sibling file and then renamed
 * over the registry file to avoid corrupt state on crash.
 */
@ApplicationScoped
public class SubscriptionPersistence {

    private static final Logger logger = Logger.getLogger(SubscriptionPersistence.class);
    private static final TypeReference<Set<String>> SET_STRING = new TypeReference<>() {};

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "gateway.subscription.registry.path", defaultValue = "data/subscriptions.json")
    String registryPath;

    private final Set<String> registry = new LinkedHashSet<>();

    @PostConstruct
    void init() {
        Path path = Path.of(registryPath);
        if (!Files.exists(path)) return;
        try {
            Set<String> loaded = objectMapper.readValue(path.toFile(), SET_STRING);
            if (loaded != null) registry.addAll(loaded);
            logger.infof("Loaded %d persisted subscription(s) from %s", registry.size(), registryPath);
        } catch (IOException e) {
            logger.warnf(e, "Could not read subscription registry %s — starting empty", registryPath);
        }
    }

    public synchronized void save(String requestName) {
        registry.add(requestName);
        persist();
    }

    public synchronized void remove(String requestName) {
        registry.remove(requestName);
        persist();
    }

    public synchronized List<String> loadAll() {
        return List.copyOf(registry);
    }

    private void persist() {
        try {
            Path target = Path.of(registryPath);
            Files.createDirectories(target.getParent() != null ? target.getParent() : Path.of("."));
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            objectMapper.writeValue(tmp.toFile(), registry);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.warnf(e, "Failed to persist subscription registry to %s", registryPath);
        }
    }
}
