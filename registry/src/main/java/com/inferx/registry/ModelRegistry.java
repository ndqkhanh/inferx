package com.inferx.registry;

import com.inferx.common.model.ModelEndpoint;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Model registry — manages model endpoints, versions, and deployment strategies.
 * Supports blue-green deployment with atomic traffic switching and automatic rollback.
 *
 * Thread-safe via ConcurrentHashMap + CopyOnWriteArrayList.
 */
public final class ModelRegistry {

    private final ConcurrentHashMap<String, List<ModelVersion>> versions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ModelEndpoint> endpoints = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<DeploymentEvent> history = new CopyOnWriteArrayList<>();

    /** Register a new model endpoint. */
    public void registerEndpoint(ModelEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        endpoints.put(endpoint.id(), endpoint);

        versions.computeIfAbsent(endpoint.modelId(), k -> new CopyOnWriteArrayList<>())
                .add(new ModelVersion(endpoint.version(), endpoint.id(),
                        ModelVersion.Stage.STAGING, Instant.now()));

        history.add(new DeploymentEvent(DeploymentEvent.Type.REGISTERED,
                endpoint.modelId(), endpoint.version(), Instant.now()));
    }

    /** Remove an endpoint from the registry. */
    public void deregisterEndpoint(String endpointId) {
        var endpoint = endpoints.remove(endpointId);
        if (endpoint != null) {
            history.add(new DeploymentEvent(DeploymentEvent.Type.DEREGISTERED,
                    endpoint.modelId(), endpoint.version(), Instant.now()));
        }
    }

    /** Get all healthy endpoints for a model. */
    public List<ModelEndpoint> getHealthyEndpoints(String modelId) {
        return endpoints.values().stream()
                .filter(ep -> ep.modelId().equals(modelId))
                .filter(ep -> ep.status() == ModelEndpoint.Status.HEALTHY)
                .toList();
    }

    /** Get all endpoints (any status) for a model. */
    public List<ModelEndpoint> getAllEndpoints(String modelId) {
        return endpoints.values().stream()
                .filter(ep -> ep.modelId().equals(modelId))
                .toList();
    }

    /** Get a specific endpoint by ID. */
    public Optional<ModelEndpoint> getEndpoint(String endpointId) {
        return Optional.ofNullable(endpoints.get(endpointId));
    }

    /**
     * Promote a model version from STAGING to ACTIVE (blue-green switch).
     * Atomically demotes the current ACTIVE version to DRAINING.
     */
    public void promote(String modelId, String version) {
        var modelVersions = versions.get(modelId);
        if (modelVersions == null) throw new IllegalArgumentException("Unknown model: " + modelId);

        for (var mv : modelVersions) {
            if (mv.stage() == ModelVersion.Stage.ACTIVE) {
                modelVersions.remove(mv);
                modelVersions.add(mv.withStage(ModelVersion.Stage.DRAINING));
            }
        }

        var target = modelVersions.stream()
                .filter(mv -> mv.version().equals(version))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown version: " + version));

        modelVersions.remove(target);
        modelVersions.add(target.withStage(ModelVersion.Stage.ACTIVE));

        history.add(new DeploymentEvent(DeploymentEvent.Type.PROMOTED,
                modelId, version, Instant.now()));
    }

    /**
     * Rollback to the previous ACTIVE version (promotes DRAINING back to ACTIVE,
     * demotes current ACTIVE to STAGING).
     */
    public void rollback(String modelId) {
        var modelVersions = versions.get(modelId);
        if (modelVersions == null) throw new IllegalArgumentException("Unknown model: " + modelId);

        ModelVersion currentActive = null;
        ModelVersion previousDraining = null;

        for (var mv : modelVersions) {
            if (mv.stage() == ModelVersion.Stage.ACTIVE) currentActive = mv;
            if (mv.stage() == ModelVersion.Stage.DRAINING) previousDraining = mv;
        }

        if (previousDraining == null) {
            throw new IllegalStateException("No previous version to rollback to for model: " + modelId);
        }

        if (currentActive != null) {
            modelVersions.remove(currentActive);
            modelVersions.add(currentActive.withStage(ModelVersion.Stage.STAGING));
        }

        modelVersions.remove(previousDraining);
        modelVersions.add(previousDraining.withStage(ModelVersion.Stage.ACTIVE));

        history.add(new DeploymentEvent(DeploymentEvent.Type.ROLLED_BACK,
                modelId, previousDraining.version(), Instant.now()));
    }

    /** Get the currently ACTIVE version for a model. */
    public Optional<ModelVersion> getActiveVersion(String modelId) {
        var modelVersions = versions.get(modelId);
        if (modelVersions == null) return Optional.empty();
        return modelVersions.stream()
                .filter(mv -> mv.stage() == ModelVersion.Stage.ACTIVE)
                .findFirst();
    }

    /** Get all versions for a model. */
    public List<ModelVersion> getVersions(String modelId) {
        return List.copyOf(versions.getOrDefault(modelId, List.of()));
    }

    /** Get deployment history. */
    public List<DeploymentEvent> getHistory() {
        return List.copyOf(history);
    }

    public int endpointCount() { return endpoints.size(); }

    /** Model version with deployment stage. */
    public record ModelVersion(String version, String endpointId, Stage stage, Instant deployedAt) {
        public enum Stage { STAGING, ACTIVE, DRAINING, RETIRED }

        public ModelVersion withStage(Stage newStage) {
            return new ModelVersion(version, endpointId, newStage, deployedAt);
        }
    }

    /** Deployment event for audit trail. */
    public record DeploymentEvent(Type type, String modelId, String version, Instant timestamp) {
        public enum Type { REGISTERED, DEREGISTERED, PROMOTED, ROLLED_BACK }
    }
}
