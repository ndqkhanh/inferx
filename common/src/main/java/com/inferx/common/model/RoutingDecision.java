package com.inferx.common.model;

import java.time.Instant;
import java.util.Objects;

/**
 * The result of a routing decision — which endpoint to send a request to.
 *
 * @param requestId  the routed request
 * @param endpoint   selected model endpoint
 * @param strategy   which routing strategy produced this decision
 * @param confidence confidence score (0.0 to 1.0) from the routing algorithm
 * @param decidedAt  when the routing decision was made
 */
public record RoutingDecision(
        String requestId,
        ModelEndpoint endpoint,
        Strategy strategy,
        double confidence,
        Instant decidedAt
) {
    public RoutingDecision {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(strategy, "strategy");
        if (decidedAt == null) decidedAt = Instant.now();
    }

    /** Routing strategies supported by the router module. */
    public enum Strategy {
        ROUND_ROBIN,
        WEIGHTED,
        THOMPSON_SAMPLING,
        CANARY,
        SHADOW,
        INPUT_BASED
    }
}
