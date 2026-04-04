package com.inferx.common.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ModelEndpointTest {

    @Test
    void validEndpointCreation() {
        var ep = new ModelEndpoint(
                "ep-1", "gpt-4", "v1.0", "localhost", 8080,
                ModelEndpoint.Status.HEALTHY, 0.5, Map.of("gpu", "a100")
        );
        assertThat(ep.address()).isEqualTo("localhost:8080");
        assertThat(ep.capabilities()).containsEntry("gpu", "a100");
    }

    @Test
    void rejectsInvalidPort() {
        assertThatThrownBy(() -> new ModelEndpoint(
                "ep-1", "m", "v1", "h", -1, ModelEndpoint.Status.HEALTHY, 0.5, Map.of()
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ModelEndpoint(
                "ep-1", "m", "v1", "h", 70000, ModelEndpoint.Status.HEALTHY, 0.5, Map.of()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1, 2.0})
    void rejectsInvalidWeight(double weight) {
        assertThatThrownBy(() -> new ModelEndpoint(
                "ep-1", "m", "v1", "h", 8080, ModelEndpoint.Status.HEALTHY, weight, Map.of()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void capabilitiesAreImmutable() {
        var caps = new java.util.HashMap<String, String>();
        caps.put("gpu", "a100");
        var ep = new ModelEndpoint("ep-1", "m", "v1", "h", 8080,
                ModelEndpoint.Status.HEALTHY, 0.5, caps);
        assertThatThrownBy(() -> ep.capabilities().put("new", "val"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullCapabilitiesDefaultToEmpty() {
        var ep = new ModelEndpoint("ep-1", "m", "v1", "h", 8080,
                ModelEndpoint.Status.HEALTHY, 0.5, null);
        assertThat(ep.capabilities()).isEmpty();
    }

    @Test
    void allStatusValues() {
        assertThat(ModelEndpoint.Status.values()).hasSize(4);
    }
}
