package com.inferx.router;

import com.inferx.common.model.InferRequest;
import com.inferx.common.model.ModelEndpoint;
import com.inferx.common.model.RoutingDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class WeightedRoundRobinRouterTest {

    private final WeightedRoundRobinRouter router = new WeightedRoundRobinRouter();

    @Test
    void routesToHealthyEndpoint() {
        var eps = List.of(endpoint("ep-1", 0.5));
        var decision = router.route(textRequest("r1"), eps);
        assertThat(decision).isPresent();
        assertThat(decision.get().strategy()).isEqualTo(RoutingDecision.Strategy.WEIGHTED);
    }

    @Test
    void returnsEmptyForNoEndpoints() {
        assertThat(router.route(textRequest("r1"), List.of())).isEmpty();
        assertThat(router.route(textRequest("r1"), null)).isEmpty();
    }

    @Test
    void skipsUnhealthyEndpoints() {
        var eps = List.of(
                new ModelEndpoint("ep-1", "m", "v1", "h", 8080,
                        ModelEndpoint.Status.UNHEALTHY, 0.5, Map.of()),
                endpoint("ep-2", 0.5)
        );
        var decision = router.route(textRequest("r1"), eps);
        assertThat(decision).isPresent();
        assertThat(decision.get().endpoint().id()).isEqualTo("ep-2");
    }

    @Test
    void distributesProportionallyToWeight() {
        var eps = List.of(
                endpoint("ep-heavy", 0.8),
                endpoint("ep-light", 0.2)
        );

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            var decision = router.route(textRequest("r-" + i), eps).orElseThrow();
            counts.merge(decision.endpoint().id(), 1, Integer::sum);
        }

        int heavy = counts.getOrDefault("ep-heavy", 0);
        int light = counts.getOrDefault("ep-light", 0);
        // Heavy should get roughly 4x the traffic of light
        assertThat((double) heavy / light).isBetween(2.5, 6.0);
    }

    @Test
    void equalWeightDistributesEvenly() {
        var eps = List.of(
                endpoint("ep-a", 0.5),
                endpoint("ep-b", 0.5)
        );

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            var decision = router.route(textRequest("r-" + i), eps).orElseThrow();
            counts.merge(decision.endpoint().id(), 1, Integer::sum);
        }

        int a = counts.getOrDefault("ep-a", 0);
        int b = counts.getOrDefault("ep-b", 0);
        assertThat((double) a / b).isBetween(0.7, 1.3);
    }

    // --- helpers ---

    private static ModelEndpoint endpoint(String id, double weight) {
        return new ModelEndpoint(id, "model", "v1", "localhost", 8080,
                ModelEndpoint.Status.HEALTHY, weight, Map.of());
    }

    private static InferRequest textRequest(String id) {
        return new InferRequest(id, "model", new InferRequest.Input.Text("test"),
                Map.of(), null, 1, Map.of(), Instant.now());
    }
}
