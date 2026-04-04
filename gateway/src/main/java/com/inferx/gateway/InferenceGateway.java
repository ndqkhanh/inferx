package com.inferx.gateway;

import com.inferx.batcher.DeadlineAwareBatcher;
import com.inferx.common.error.InferXException;
import com.inferx.common.model.InferRequest;
import com.inferx.common.model.InferResponse;
import com.inferx.common.model.ModelEndpoint;
import com.inferx.common.model.RoutingDecision;
import com.inferx.registry.ModelRegistry;
import com.inferx.router.ModelRouter;
import com.inferx.scheduler.EdfScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * The main inference gateway — orchestrates routing, batching, scheduling,
 * and model invocation for incoming inference requests.
 *
 * Implements token-bucket admission control to prevent overload.
 */
public final class InferenceGateway {

    private final ModelRouter router;
    private final ModelRegistry registry;
    private final EdfScheduler scheduler;
    private final DeadlineAwareBatcher batcher;
    private final TokenBucket admissionControl;
    private final Function<RoutingDecision, InferResponse> modelInvoker;

    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong totalAdmitted = new AtomicLong(0);
    private final AtomicLong totalRejected = new AtomicLong(0);
    private final AtomicLong totalSucceeded = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);

    /**
     * @param router        model routing strategy
     * @param registry      model endpoint registry
     * @param scheduler     EDF request scheduler
     * @param batcher       deadline-aware batcher
     * @param maxRps        maximum requests per second (admission control)
     * @param modelInvoker  function to invoke a model given a routing decision
     */
    public InferenceGateway(ModelRouter router, ModelRegistry registry,
                            EdfScheduler scheduler, DeadlineAwareBatcher batcher,
                            long maxRps,
                            Function<RoutingDecision, InferResponse> modelInvoker) {
        this.router = Objects.requireNonNull(router);
        this.registry = Objects.requireNonNull(registry);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.batcher = Objects.requireNonNull(batcher);
        this.admissionControl = new TokenBucket(maxRps, maxRps);
        this.modelInvoker = Objects.requireNonNull(modelInvoker);
    }

    /**
     * Process an inference request end-to-end:
     * 1. Admission control (token bucket)
     * 2. Route to model endpoint (Thompson Sampling)
     * 3. Schedule by deadline (EDF)
     * 4. Invoke model
     * 5. Record outcome for router learning
     */
    public InferResponse infer(InferRequest request) {
        totalRequests.incrementAndGet();

        // Step 1: Admission control
        if (!admissionControl.tryAcquire()) {
            totalRejected.incrementAndGet();
            throw new InferXException.BackpressureViolation("gateway-admission");
        }
        totalAdmitted.incrementAndGet();

        // Step 2: Route
        List<ModelEndpoint> endpoints = registry.getHealthyEndpoints(request.modelId());
        RoutingDecision decision = router.route(request, endpoints)
                .orElseThrow(() -> new InferXException.RoutingError(request.modelId()));

        // Step 3: Schedule
        scheduler.submit(request);

        // Step 4: Invoke model
        Instant start = Instant.now();
        try {
            InferResponse response = modelInvoker.apply(decision);
            long latencyMs = Duration.between(start, Instant.now()).toMillis();

            // Step 5: Record outcome for adaptive routing
            router.recordOutcome(decision, latencyMs, true);
            totalSucceeded.incrementAndGet();
            return response;
        } catch (Exception e) {
            long latencyMs = Duration.between(start, Instant.now()).toMillis();
            router.recordOutcome(decision, latencyMs, false);
            totalFailed.incrementAndGet();
            throw e;
        }
    }

    /**
     * Get a snapshot of gateway statistics.
     */
    public GatewayStats stats() {
        return new GatewayStats(
                totalRequests.get(),
                totalAdmitted.get(),
                totalRejected.get(),
                totalSucceeded.get(),
                totalFailed.get(),
                scheduler.queueSize()
        );
    }

    public record GatewayStats(
            long totalRequests, long totalAdmitted, long totalRejected,
            long totalSucceeded, long totalFailed, int queueDepth) {

        public double successRate() {
            long completed = totalSucceeded + totalFailed;
            return completed > 0 ? (double) totalSucceeded / completed : 0.0;
        }

        public double admissionRate() {
            return totalRequests > 0 ? (double) totalAdmitted / totalRequests : 0.0;
        }
    }

    /**
     * Token bucket rate limiter for admission control.
     * Thread-safe via CAS loop.
     */
    static final class TokenBucket {
        private final long maxTokens;
        private final long refillRate; // tokens per second
        private volatile long tokens;
        private volatile long lastRefillNanos;

        TokenBucket(long maxTokens, long refillRate) {
            this.maxTokens = maxTokens;
            this.refillRate = refillRate;
            this.tokens = maxTokens;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryAcquire() {
            refill();
            if (tokens > 0) {
                tokens--;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.nanoTime();
            long elapsed = now - lastRefillNanos;
            long newTokens = (elapsed * refillRate) / 1_000_000_000L;
            if (newTokens > 0) {
                tokens = Math.min(maxTokens, tokens + newTokens);
                lastRefillNanos = now;
            }
        }
    }
}
