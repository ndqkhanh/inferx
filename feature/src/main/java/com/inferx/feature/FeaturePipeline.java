package com.inferx.feature;

import com.inferx.common.model.StreamEvent;
import com.inferx.stream.Operator;
import com.inferx.stream.StreamPipeline;
import com.inferx.stream.WindowStrategy;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real-time feature computation pipeline built on top of the stream engine.
 *
 * Extracts features from inference request events, maintains a feature store
 * with point-in-time joins, and caches computed features.
 */
public final class FeaturePipeline {

    private final StreamPipeline pipeline;
    private final ConcurrentHashMap<String, FeatureVector> featureStore = new ConcurrentHashMap<>();
    private final List<StreamEvent> sinkOutput = Collections.synchronizedList(new ArrayList<>());

    public FeaturePipeline(Duration windowSize, long maxLatenessMs) {
        this.pipeline = new StreamPipeline(List.of(
                new Operator.Map("extract-features", this::extractFeatures),
                new Operator.Window("feature-window", new WindowStrategy.Tumbling(windowSize)),
                new Operator.Sink("feature-sink", sinkOutput::add)
        ), maxLatenessMs);
    }

    /**
     * Ingest a raw event and compute features.
     *
     * @return computed feature events (may be empty if windowing buffers)
     */
    public List<StreamEvent> ingest(StreamEvent event) {
        var results = pipeline.process(event);

        // Update feature store with latest computed features
        for (var result : results) {
            if (result.value() instanceof Map) {
                featureStore.put(result.key(), new FeatureVector(
                        result.key(), Map.copyOf((Map<String, Object>) result.value()),
                        result.eventTime()));
            }
        }

        return results;
    }

    /**
     * Point-in-time feature lookup — returns the latest feature vector
     * for a given key that was computed BEFORE the specified timestamp.
     * Prevents data leakage by never returning future features.
     */
    public Optional<FeatureVector> getFeatures(String key, Instant asOf) {
        var fv = featureStore.get(key);
        if (fv == null) return Optional.empty();
        if (fv.computedAt().isAfter(asOf)) return Optional.empty();
        return Optional.of(fv);
    }

    /** Get the latest feature vector for a key (no time constraint). */
    public Optional<FeatureVector> getLatestFeatures(String key) {
        return Optional.ofNullable(featureStore.get(key));
    }

    /** Trigger windows and collect aggregated feature results. */
    public List<StreamEvent> triggerWindows() {
        return pipeline.triggerWindows();
    }

    private StreamEvent extractFeatures(StreamEvent event) {
        // Extract features from the raw event value
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("key", event.key());
        features.put("event_time", event.eventTime().toEpochMilli());
        features.put("lag_ms", event.lagMs());

        if (event.value() instanceof String s) {
            features.put("text_length", s.length());
            features.put("word_count", s.split("\\s+").length);
        } else if (event.value() instanceof Number n) {
            features.put("numeric_value", n.doubleValue());
        }

        features.putAll(event.attributes());

        return new StreamEvent(event.key(), features, event.eventTime());
    }

    public int featureStoreSize() { return featureStore.size(); }
    public long processedCount() { return pipeline.processedCount(); }

    /** Immutable feature vector stored in the feature store. */
    public record FeatureVector(String key, Map<String, Object> features, Instant computedAt) {
        public FeatureVector {
            Objects.requireNonNull(key);
            features = Map.copyOf(features);
            Objects.requireNonNull(computedAt);
        }
    }
}
