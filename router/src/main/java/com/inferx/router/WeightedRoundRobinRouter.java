package com.inferx.router;

import com.inferx.common.model.InferRequest;
import com.inferx.common.model.ModelEndpoint;
import com.inferx.common.model.RoutingDecision;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Weighted round-robin router — distributes requests proportionally to endpoint weights.
 * Deterministic and simple; used as baseline or fallback.
 */
public final class WeightedRoundRobinRouter implements ModelRouter {

    private final AtomicLong counter = new AtomicLong(0);

    @Override
    public Optional<RoutingDecision> route(InferRequest request, List<ModelEndpoint> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) return Optional.empty();

        var healthy = endpoints.stream()
                .filter(ep -> ep.status() == ModelEndpoint.Status.HEALTHY)
                .toList();

        if (healthy.isEmpty()) return Optional.empty();

        // Weighted selection: expand endpoints by weight (scaled to integers)
        double totalWeight = healthy.stream().mapToDouble(ModelEndpoint::weight).sum();
        if (totalWeight <= 0) {
            // Equal weight fallback
            int idx = (int) (counter.getAndIncrement() % healthy.size());
            return Optional.of(new RoutingDecision(
                    request.id(), healthy.get(idx),
                    RoutingDecision.Strategy.WEIGHTED, 1.0, Instant.now()
            ));
        }

        long tick = counter.getAndIncrement();
        double point = (tick % 1000) / 1000.0 * totalWeight;
        double cumulative = 0;

        for (ModelEndpoint ep : healthy) {
            cumulative += ep.weight();
            if (point < cumulative) {
                return Optional.of(new RoutingDecision(
                        request.id(), ep,
                        RoutingDecision.Strategy.WEIGHTED,
                        ep.weight() / totalWeight,
                        Instant.now()
                ));
            }
        }

        // Fallback to last
        return Optional.of(new RoutingDecision(
                request.id(), healthy.getLast(),
                RoutingDecision.Strategy.WEIGHTED, 1.0, Instant.now()
        ));
    }
}
