package com.inferx.common.model;

import java.util.Map;
import java.util.Objects;

/**
 * A registered model endpoint that can serve inference requests.
 *
 * @param id          unique endpoint identifier
 * @param modelId     logical model name
 * @param version     model version string
 * @param host        network host
 * @param port        network port
 * @param status      current health status
 * @param weight      traffic weight for routing (0.0 to 1.0)
 * @param capabilities model capabilities metadata
 */
public record ModelEndpoint(
        String id,
        String modelId,
        String version,
        String host,
        int port,
        Status status,
        double weight,
        Map<String, String> capabilities
) {
    public ModelEndpoint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(host, "host");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid port: " + port);
        if (weight < 0.0 || weight > 1.0) throw new IllegalArgumentException("Weight must be in [0,1]: " + weight);
        capabilities = capabilities != null ? Map.copyOf(capabilities) : Map.of();
    }

    public enum Status {
        HEALTHY, UNHEALTHY, DRAINING, STARTING
    }

    public String address() {
        return host + ":" + port;
    }
}
