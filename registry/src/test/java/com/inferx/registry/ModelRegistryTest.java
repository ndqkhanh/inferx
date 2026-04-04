package com.inferx.registry;

import com.inferx.common.model.ModelEndpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ModelRegistryTest {

    private ModelRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ModelRegistry();
    }

    @Test
    void registerAndRetrieveEndpoint() {
        registry.registerEndpoint(endpoint("ep-1", "gpt-4", "v1"));
        assertThat(registry.getEndpoint("ep-1")).isPresent();
        assertThat(registry.endpointCount()).isEqualTo(1);
    }

    @Test
    void getHealthyEndpointsFiltersUnhealthy() {
        registry.registerEndpoint(endpoint("ep-1", "gpt-4", "v1"));
        registry.registerEndpoint(unhealthyEndpoint("ep-2", "gpt-4", "v2"));

        var healthy = registry.getHealthyEndpoints("gpt-4");
        assertThat(healthy).hasSize(1);
        assertThat(healthy.getFirst().id()).isEqualTo("ep-1");
    }

    @Test
    void getAllEndpointsIncludesAll() {
        registry.registerEndpoint(endpoint("ep-1", "gpt-4", "v1"));
        registry.registerEndpoint(unhealthyEndpoint("ep-2", "gpt-4", "v2"));

        assertThat(registry.getAllEndpoints("gpt-4")).hasSize(2);
    }

    @Test
    void deregisterRemovesEndpoint() {
        registry.registerEndpoint(endpoint("ep-1", "gpt-4", "v1"));
        registry.deregisterEndpoint("ep-1");

        assertThat(registry.getEndpoint("ep-1")).isEmpty();
        assertThat(registry.endpointCount()).isZero();
    }

    @Test
    void promoteMovesToActive() {
        registry.registerEndpoint(endpoint("ep-1", "gpt-4", "v1"));

        registry.promote("gpt-4", "v1");

        var active = registry.getActiveVersion("gpt-4");
        assertThat(active).isPresent();
        assertThat(active.get().version()).isEqualTo("v1");
        assertThat(active.get().stage()).isEqualTo(ModelRegistry.ModelVersion.Stage.ACTIVE);
    }

    @Test
    void promoteNewVersionDrainsOld() {
        registry.registerEndpoint(endpoint("ep-1", "gpt-4", "v1"));
        registry.registerEndpoint(endpoint("ep-2", "gpt-4", "v2"));

        registry.promote("gpt-4", "v1");
        registry.promote("gpt-4", "v2");

        var active = registry.getActiveVersion("gpt-4");
        assertThat(active).isPresent();
        assertThat(active.get().version()).isEqualTo("v2");

        // v1 should be DRAINING
        var versions = registry.getVersions("gpt-4");
        var v1 = versions.stream().filter(v -> v.version().equals("v1")).findFirst().orElseThrow();
        assertThat(v1.stage()).isEqualTo(ModelRegistry.ModelVersion.Stage.DRAINING);
    }

    @Test
    void rollbackRestoresPreviousVersion() {
        registry.registerEndpoint(endpoint("ep-1", "gpt-4", "v1"));
        registry.registerEndpoint(endpoint("ep-2", "gpt-4", "v2"));

        registry.promote("gpt-4", "v1");
        registry.promote("gpt-4", "v2"); // v1 → DRAINING, v2 → ACTIVE
        registry.rollback("gpt-4");      // v2 → STAGING, v1 → ACTIVE

        var active = registry.getActiveVersion("gpt-4");
        assertThat(active).isPresent();
        assertThat(active.get().version()).isEqualTo("v1");
    }

    @Test
    void rollbackFailsWithNoPreviousVersion() {
        registry.registerEndpoint(endpoint("ep-1", "gpt-4", "v1"));
        registry.promote("gpt-4", "v1");

        assertThatThrownBy(() -> registry.rollback("gpt-4"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void promoteFailsForUnknownModel() {
        assertThatThrownBy(() -> registry.promote("nonexistent", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void promoteFailsForUnknownVersion() {
        registry.registerEndpoint(endpoint("ep-1", "gpt-4", "v1"));
        assertThatThrownBy(() -> registry.promote("gpt-4", "v999"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void historyTracksAllEvents() {
        registry.registerEndpoint(endpoint("ep-1", "gpt-4", "v1"));
        registry.promote("gpt-4", "v1");

        var history = registry.getHistory();
        assertThat(history).hasSize(2);
        assertThat(history.get(0).type()).isEqualTo(ModelRegistry.DeploymentEvent.Type.REGISTERED);
        assertThat(history.get(1).type()).isEqualTo(ModelRegistry.DeploymentEvent.Type.PROMOTED);
    }

    @Test
    void historyIsImmutable() {
        registry.registerEndpoint(endpoint("ep-1", "gpt-4", "v1"));
        var history = registry.getHistory();
        assertThatThrownBy(() -> history.add(
                new ModelRegistry.DeploymentEvent(
                        ModelRegistry.DeploymentEvent.Type.REGISTERED, "x", "v", java.time.Instant.now())))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void noActiveVersionInitially() {
        registry.registerEndpoint(endpoint("ep-1", "gpt-4", "v1"));
        assertThat(registry.getActiveVersion("gpt-4")).isEmpty(); // still STAGING
    }

    @Test
    void emptyModelReturnsNoEndpoints() {
        assertThat(registry.getHealthyEndpoints("nonexistent")).isEmpty();
        assertThat(registry.getVersions("nonexistent")).isEmpty();
    }

    // --- helpers ---

    private static ModelEndpoint endpoint(String id, String modelId, String version) {
        return new ModelEndpoint(id, modelId, version, "localhost", 8080,
                ModelEndpoint.Status.HEALTHY, 0.5, Map.of());
    }

    private static ModelEndpoint unhealthyEndpoint(String id, String modelId, String version) {
        return new ModelEndpoint(id, modelId, version, "localhost", 8080,
                ModelEndpoint.Status.UNHEALTHY, 0.5, Map.of());
    }
}
