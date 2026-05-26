package org.moqui.device.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.moqui.device.gateway.service.SubscriptionPersistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;

/**
 * Tests for {@link SubscriptionPersistence} — file-backed subscription registry.
 *
 * Verifies save / remove / loadAll behaviour and the JSON file format written by
 * the atomic-move persist mechanism, simulating the data that a fresh instance
 * would reload from disk on gateway restart.
 *
 * Does NOT require an MQTT broker or DB operations.
 * Requires PostgreSQL on localhost:5432 (Quarkus datasource initialization only).
 *
 * Run:
 *   mvn test -Dquarkus.profile=integration -Dtest=SubscriptionPersistenceTest
 */
@QuarkusTest
@TestProfile(SubscriptionPersistenceTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SubscriptionPersistenceTest {

    private static final String REGISTRY_PATH = SubscriptionPersistenceTestProfile.REGISTRY_PATH;

    @Inject
    SubscriptionPersistence persistence;

    @Inject
    ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // Lifecycle — clear in-memory registry and file before each test
    // -------------------------------------------------------------------------

    @BeforeEach
    void cleanup() throws Exception {
        // Remove all currently tracked subscriptions to reset in-memory state
        List<String> current = persistence.loadAll();
        current.forEach(persistence::remove);
        Files.deleteIfExists(Path.of(REGISTRY_PATH));
    }

    // =========================================================================
    // Test cases
    // =========================================================================

    @Test
    @Order(1)
    void saveAddsSubscriptionToInMemoryRegistry() {
        persistence.save("REQ_A");
        persistence.save("REQ_B");

        List<String> loaded = persistence.loadAll();
        assertTrue(loaded.contains("REQ_A"), "REQ_A must be in the registry after save()");
        assertTrue(loaded.contains("REQ_B"), "REQ_B must be in the registry after save()");
        assertEquals(2, loaded.size());
    }

    @Test
    @Order(2)
    void removeDeletesSubscriptionFromInMemoryRegistry() {
        persistence.save("REQ_X");
        persistence.save("REQ_Y");
        persistence.remove("REQ_X");

        List<String> loaded = persistence.loadAll();
        assertFalse(loaded.contains("REQ_X"), "REQ_X must not be present after remove()");
        assertTrue(loaded.contains("REQ_Y"),  "REQ_Y must remain after removing REQ_X");
        assertEquals(1, loaded.size());
    }

    @Test
    @Order(3)
    void loadAllReturnsEmptyListWhenNoSubscriptionsRegistered() {
        assertEquals(0, persistence.loadAll().size(),
            "Registry must be empty after cleanup");
    }

    @Test
    @Order(4)
    void saveWritesSubscriptionToJsonFile() throws Exception {
        persistence.save("MY_REQ_1");

        Path file = Path.of(REGISTRY_PATH);
        assertTrue(Files.exists(file), "Registry file must exist after save()");

        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(json.contains("MY_REQ_1"),
            "JSON file must contain the saved subscription name. Content: " + json);
    }

    @Test
    @Order(5)
    void removeUpdatesJsonFileOnDisk() throws Exception {
        persistence.save("KEEP_ME");
        persistence.save("REMOVE_ME");
        persistence.remove("REMOVE_ME");

        Path file = Path.of(REGISTRY_PATH);
        assertTrue(Files.exists(file), "Registry file must still exist after partial remove");

        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(json.contains("KEEP_ME"),    "KEEP_ME must remain in JSON file");
        assertFalse(json.contains("REMOVE_ME"), "REMOVE_ME must be absent from JSON file");
    }

    /**
     * Simulates a gateway restart: verifies that the JSON file contains exactly the
     * subscriptions that a newly instantiated SubscriptionPersistence would reload
     * via its @PostConstruct init().
     */
    @Test
    @Order(6)
    void persistedFileSurvivesSimulatedRestart() throws Exception {
        persistence.save("REQ_RESTART_A");
        persistence.save("REQ_RESTART_B");

        Path file = Path.of(REGISTRY_PATH);
        assertTrue(Files.exists(file), "Registry file must exist");

        // Read the file directly — this mirrors what @PostConstruct does on restart
        Set<String> reloaded = objectMapper.readValue(file.toFile(), new TypeReference<>() {});
        assertTrue(reloaded.contains("REQ_RESTART_A"),
            "REQ_RESTART_A must survive a simulated restart");
        assertTrue(reloaded.contains("REQ_RESTART_B"),
            "REQ_RESTART_B must survive a simulated restart");
        assertEquals(2, reloaded.size(),
            "Exactly 2 subscriptions must be present after restart");
    }

    /**
     * Duplicate save() calls for the same requestName must not produce duplicates
     * (LinkedHashSet semantics).
     */
    @Test
    @Order(7)
    void saveSameNameTwiceProducesNoDuplicate() throws Exception {
        persistence.save("DEDUP_REQ");
        persistence.save("DEDUP_REQ");

        assertEquals(1, persistence.loadAll().size(),
            "Same requestName saved twice must not produce a duplicate entry");

        Set<String> reloaded = objectMapper.readValue(
            Path.of(REGISTRY_PATH).toFile(), new TypeReference<>() {});
        assertEquals(1, reloaded.size(),
            "JSON file must also contain exactly one entry for the deduplicated name");
    }
}
