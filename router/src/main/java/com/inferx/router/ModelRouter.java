package com.inferx.router;

import com.inferx.common.model.InferRequest;
import com.inferx.common.model.ModelEndpoint;
import com.inferx.common.model.RoutingDecision;

import java.util.List;
import java.util.Optional;

/**
 * Routes inference requests to model endpoints.
 * Implementations may use static or adaptive strategies.
 */
public interface ModelRouter {

    /**
     * Select the best endpoint for the given request.
     *
     * @param request   the inference request
     * @param endpoints available healthy endpoints
     * @return routing decision, or empty if no suitable endpoint found
     */
    Optional<RoutingDecision> route(InferRequest request, List<ModelEndpoint> endpoints);

    /**
     * Provide feedback on a completed request to update routing state.
     * Used by adaptive routers (e.g., Thompson Sampling) to learn.
     *
     * @param decision  the routing decision that was made
     * @param latencyMs observed latency in milliseconds
     * @param success   whether the request succeeded
     */
    default void recordOutcome(RoutingDecision decision, long latencyMs, boolean success) {
        // Default no-op for static routers
    }
}
