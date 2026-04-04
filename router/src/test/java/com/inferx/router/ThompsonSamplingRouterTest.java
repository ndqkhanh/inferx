package com.inferx.router;

import com.inferx.common.model.InferRequest;
import com.inferx.common.model.ModelEndpoint;
import com.inferx.common.model.RoutingDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ThompsonSamplingRouterTest {

    private ThompsonSamplingRouter router;
    private List<ModelEndpoint> endpoints;
    private InferRequest request;

    @BeforeEach
    void setUp() {
        router = new ThompsonSamplingRouter(100); // 100ms SLO
        endpoints = List.of(
                endpoint("ep-good", "model-a", "v1"),
                endpoint("ep-bad", "model-a", "v2")
        );
        request = textRequest("req-1", "model-a");
    }

    @Test
    void routesToHealthyEndpoint() {
        var decision = router.route(request, endpoints);
        assertThat(decision).isPresent();
        assertThat(decision.get().strategy()).isEqualTo(RoutingDecision.Strategy.THOMPSON_SAMPLING);
    }

    @Test
    void returnsEmptyForNoEndpoints() {
        assertThat(router.route(request, List.of())).isEmpty();
        assertThat(router.route(request, null)).isEmpty();
    }

    @Test
    void returnsEmptyWhenAllUnhealthy() {
        var unhealthy = List.of(
                new ModelEndpoint("ep-1", "m", "v1", "h", 8080,
                        ModelEndpoint.Status.UNHEALTHY, 0.5, Map.of())
        );
        assertThat(router.route(request, unhealthy)).isEmpty();
    }

    @Test
    void learnsToPrefGoodEndpointOver1000Trials() {
        // Simulate: ep-good succeeds 90% within SLO, ep-bad succeeds 30%
        for (int i = 0; i < 1000; i++) {
            var req = textRequest("req-" + i, "model-a");
            var decision = router.route(req, endpoints);
            assertThat(decision).isPresent();

            String epId = decision.get().endpoint().id();
            boolean success;
            long latency;

            if (epId.equals("ep-good")) {
                success = Math.random() < 0.9;
                latency = success ? 50 : 200;
            } else {
                success = Math.random() < 0.3;
                latency = success ? 80 : 300;
            }
            router.recordOutcome(decision.get(), latency, success);
        }

        // After 1000 trials, the router should strongly prefer ep-good
        double goodRate = router.getEstimatedRewardRate("ep-good");
        double badRate = router.getEstimatedRewardRate("ep-bad");
        assertThat(goodRate).isGreaterThan(badRate);
        assertThat(goodRate).isGreaterThan(0.7);
        assertThat(badRate).isLessThan(0.5);
    }

    @Test
    void convergesToOptimalRouting() {
        // Run 2000 trials and count how often each is selected in last 200
        Map<String, Integer> selectionCounts = new HashMap<>();
        selectionCounts.put("ep-good", 0);
        selectionCounts.put("ep-bad", 0);

        for (int i = 0; i < 2000; i++) {
            var req = textRequest("req-" + i, "model-a");
            var decision = router.route(req, endpoints).orElseThrow();

            String epId = decision.endpoint().id();
            boolean success = epId.equals("ep-good") ? (Math.random() < 0.9) : (Math.random() < 0.3);
            long latency = success ? 50 : 200;
            router.recordOutcome(decision, latency, success);

            if (i >= 1800) {
                selectionCounts.merge(epId, 1, Integer::sum);
            }
        }

        // In the last 200 trials, ep-good should be selected >70% of the time
        int goodSelections = selectionCounts.get("ep-good");
        assertThat(goodSelections).isGreaterThan(140); // >70% of 200
    }

    @Test
    void observationCountTracking() {
        assertThat(router.getObservationCount("ep-good")).isZero();

        var decision = router.route(request, endpoints).orElseThrow();
        router.recordOutcome(decision, 50, true);

        assertThat(router.getObservationCount(decision.endpoint().id())).isEqualTo(1);
    }

    @Test
    void uniformPriorBeforeAnyObservations() {
        assertThat(router.getEstimatedRewardRate("ep-good")).isEqualTo(0.5);
        assertThat(router.getEstimatedRewardRate("ep-bad")).isEqualTo(0.5);
    }

    @Test
    void sloThresholdAffectsReward() {
        var strictRouter = new ThompsonSamplingRouter(20); // very strict 20ms SLO
        var decision = new RoutingDecision("req-1", endpoints.get(0),
                RoutingDecision.Strategy.THOMPSON_SAMPLING, 0.5, Instant.now());

        // 50ms latency is a success in normal router but failure in strict router
        strictRouter.recordOutcome(decision, 50, true);
        // The arm should have recorded a failure (latency > 20ms SLO)
        assertThat(strictRouter.getEstimatedRewardRate(endpoints.get(0).id())).isLessThan(0.5);
    }

    @Test
    void rejectsInvalidSloThreshold() {
        assertThatThrownBy(() -> new ThompsonSamplingRouter(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ThompsonSamplingRouter(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handlesThreeEndpoints() {
        var eps = List.of(
                endpoint("ep-a", "m", "v1"),
                endpoint("ep-b", "m", "v2"),
                endpoint("ep-c", "m", "v3")
        );

        for (int i = 0; i < 2000; i++) {
            var req = textRequest("req-" + i, "m");
            var decision = router.route(req, eps).orElseThrow();
            // ep-a: 95% success, ep-b: 50%, ep-c: 5%
            String epId = decision.endpoint().id();
            double successRate = switch (epId) {
                case "ep-a" -> 0.95;
                case "ep-b" -> 0.50;
                default -> 0.05;
            };
            router.recordOutcome(decision, 50, Math.random() < successRate);
        }

        // With 2000 trials and wide gaps, the best arm should clearly dominate
        assertThat(router.getEstimatedRewardRate("ep-a")).isGreaterThan(router.getEstimatedRewardRate("ep-c"));
    }

    // --- helpers ---

    private static ModelEndpoint endpoint(String id, String modelId, String version) {
        return new ModelEndpoint(id, modelId, version, "localhost", 8080,
                ModelEndpoint.Status.HEALTHY, 0.5, Map.of());
    }

    private static InferRequest textRequest(String id, String modelId) {
        return new InferRequest(id, modelId, new InferRequest.Input.Text("test"),
                Map.of(), Instant.now().plusSeconds(10), 1, Map.of(), Instant.now());
    }
}
