package com.inferx.feature;

import com.inferx.common.model.StreamEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class FeaturePipelineTest {

    @Test
    void ingestExtractsTextFeatures() {
        var pipeline = new FeaturePipeline(Duration.ofSeconds(10), 0);
        var event = new StreamEvent("user-1", "hello world test", Instant.ofEpochMilli(1000));

        var results = pipeline.ingest(event);
        assertThat(results).isNotEmpty();
        assertThat(pipeline.processedCount()).isEqualTo(1);
    }

    @Test
    void ingestExtractsNumericFeatures() {
        var pipeline = new FeaturePipeline(Duration.ofSeconds(10), 0);
        var event = new StreamEvent("metric-1", 42.5, Instant.ofEpochMilli(1000));

        pipeline.ingest(event);
        assertThat(pipeline.processedCount()).isEqualTo(1);
    }

    @Test
    void featureStorePersistsLatest() {
        var pipeline = new FeaturePipeline(Duration.ofSeconds(60), 0);
        pipeline.ingest(new StreamEvent("user-1", "first", Instant.ofEpochMilli(1000)));
        pipeline.ingest(new StreamEvent("user-1", "second", Instant.ofEpochMilli(2000)));

        var latest = pipeline.getLatestFeatures("user-1");
        assertThat(latest).isPresent();
    }

    @Test
    void pointInTimeJoinPreventsLeakage() {
        var pipeline = new FeaturePipeline(Duration.ofSeconds(60), 0);
        var eventTime = Instant.ofEpochMilli(5000);
        pipeline.ingest(new StreamEvent("user-1", "data", eventTime));

        // Query as-of BEFORE the feature was computed — should return empty
        var before = pipeline.getFeatures("user-1", Instant.ofEpochMilli(4000));
        assertThat(before).isEmpty();

        // Query as-of AFTER the feature was computed — should return data
        var after = pipeline.getFeatures("user-1", Instant.ofEpochMilli(6000));
        assertThat(after).isPresent();
    }

    @Test
    void unknownKeyReturnsEmpty() {
        var pipeline = new FeaturePipeline(Duration.ofSeconds(10), 0);
        assertThat(pipeline.getLatestFeatures("nonexistent")).isEmpty();
        assertThat(pipeline.getFeatures("nonexistent", Instant.now())).isEmpty();
    }

    @Test
    void featureStoreSize() {
        var pipeline = new FeaturePipeline(Duration.ofSeconds(60), 0);
        pipeline.ingest(new StreamEvent("a", "x", Instant.ofEpochMilli(1000)));
        pipeline.ingest(new StreamEvent("b", "y", Instant.ofEpochMilli(2000)));
        assertThat(pipeline.featureStoreSize()).isEqualTo(2);
    }

    @Test
    void featureVectorIsImmutable() {
        var fv = new FeaturePipeline.FeatureVector("k", Map.of("a", "b"), Instant.now());
        assertThatThrownBy(() -> fv.features().put("c", "d"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void attributesIncludedInFeatures() {
        var pipeline = new FeaturePipeline(Duration.ofSeconds(60), 0);
        var event = new StreamEvent("user-1", "text", Instant.ofEpochMilli(1000),
                Instant.ofEpochMilli(1000), Map.of("source", "kafka"));

        pipeline.ingest(event);
        var fv = pipeline.getLatestFeatures("user-1").orElseThrow();
        assertThat(fv.features()).containsKey("source");
    }
}
