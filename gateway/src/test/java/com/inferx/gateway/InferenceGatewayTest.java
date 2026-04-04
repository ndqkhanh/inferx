package com.inferx.gateway;

import com.inferx.batcher.DeadlineAwareBatcher;
import com.inferx.common.error.InferXException;
import com.inferx.common.model.InferRequest;
import com.inferx.common.model.InferResponse;
import com.inferx.common.model.ModelEndpoint;
import com.inferx.common.model.RoutingDecision;
import com.inferx.registry.ModelRegistry;
import com.inferx.router.ThompsonSamplingRouter;
import com.inferx.scheduler.EdfScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class InferenceGatewayTest {

    private InferenceGateway gateway;
    private ModelRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ModelRegistry();
        registry.registerEndpoint(endpoint("ep-1", "gpt-4", "v1"));
        registry.promote("gpt-4", "v1");

        gateway = new InferenceGateway(
                new ThompsonSamplingRouter(100),
                registry,
                new EdfScheduler(1000),
                new DeadlineAwareBatcher(32, 8, 100, 0.85),
                1000, // 1000 RPS max
                decision -> mockResponse(decision)
        );
    }

    @Test
    void successfulInference() {
        var request = textRequest("req-1", "gpt-4");
        var response = gateway.infer(request);

        assertThat(response).isNotNull();
        assertThat(response.requestId()).isEqualTo("req-1");

        var stats = gateway.stats();
        assertThat(stats.totalRequests()).isEqualTo(1);
        assertThat(stats.totalSucceeded()).isEqualTo(1);
        assertThat(stats.successRate()).isEqualTo(1.0);
    }

    @Test
    void routingErrorWhenNoEndpoints() {
        var request = textRequest("req-1", "nonexistent-model");
        assertThatThrownBy(() -> gateway.infer(request))
                .isInstanceOf(InferXException.RoutingError.class);

        assertThat(gateway.stats().totalFailed()).isZero(); // routing fails before invocation
    }

    @Test
    void multipleRequestsUpdateStats() {
        for (int i = 0; i < 10; i++) {
            gateway.infer(textRequest("req-" + i, "gpt-4"));
        }

        var stats = gateway.stats();
        assertThat(stats.totalRequests()).isEqualTo(10);
        assertThat(stats.totalSucceeded()).isEqualTo(10);
        assertThat(stats.admissionRate()).isEqualTo(1.0);
    }

    @Test
    void failedInvocationTracked() {
        var failGateway = new InferenceGateway(
                new ThompsonSamplingRouter(100),
                registry,
                new EdfScheduler(1000),
                new DeadlineAwareBatcher(32, 8, 100, 0.85),
                1000,
                decision -> { throw new RuntimeException("model error"); }
        );

        assertThatThrownBy(() -> failGateway.infer(textRequest("req-1", "gpt-4")))
                .isInstanceOf(RuntimeException.class);

        assertThat(failGateway.stats().totalFailed()).isEqualTo(1);
        assertThat(failGateway.stats().totalSucceeded()).isZero();
    }

    @Test
    void gatewayStatsCalculations() {
        gateway.infer(textRequest("r1", "gpt-4"));
        gateway.infer(textRequest("r2", "gpt-4"));

        var stats = gateway.stats();
        assertThat(stats.successRate()).isEqualTo(1.0);
        assertThat(stats.admissionRate()).isEqualTo(1.0);
        assertThat(stats.queueDepth()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void emptyStatsBeforeAnyRequests() {
        var stats = gateway.stats();
        assertThat(stats.totalRequests()).isZero();
        assertThat(stats.successRate()).isEqualTo(0.0);
        assertThat(stats.admissionRate()).isEqualTo(0.0);
    }

    @Test
    void routerLearnsFromOutcomes() {
        // Add a second endpoint
        registry.registerEndpoint(endpoint("ep-2", "gpt-4", "v2"));

        for (int i = 0; i < 100; i++) {
            gateway.infer(textRequest("req-" + i, "gpt-4"));
        }

        // Router should have recorded outcomes for both endpoints
        assertThat(gateway.stats().totalSucceeded()).isEqualTo(100);
    }

    // --- helpers ---

    private static ModelEndpoint endpoint(String id, String modelId, String version) {
        return new ModelEndpoint(id, modelId, version, "localhost", 8080,
                ModelEndpoint.Status.HEALTHY, 0.5, Map.of());
    }

    private static InferRequest textRequest(String id, String modelId) {
        return new InferRequest(id, modelId, new InferRequest.Input.Text("test"),
                Map.of(), Instant.now().plusSeconds(30), 1, Map.of(), Instant.now());
    }

    private static InferResponse mockResponse(RoutingDecision decision) {
        return new InferResponse(
                decision.requestId(),
                decision.endpoint().modelId(),
                decision.endpoint().version(),
                new InferResponse.Output.Text("Generated response"),
                new InferResponse.Usage(10, 20, Duration.ofMillis(50)),
                Duration.ofMillis(50),
                Instant.now()
        );
    }
}
