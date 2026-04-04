package com.inferx.stream;

import com.inferx.common.model.StreamEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class StreamPipelineTest {

    @Test
    void mapOperatorTransformsEvents() {
        var outputs = new ArrayList<StreamEvent>();
        var pipeline = new StreamPipeline(List.of(
                new Operator.Map("upper", e ->
                        new StreamEvent(e.key(), e.value().toString().toUpperCase(), e.eventTime())),
                new Operator.Sink("out", outputs::add)
        ), 0);

        pipeline.process(event("k", "hello", 1000));
        assertThat(outputs).hasSize(1);
        assertThat(outputs.getFirst().value()).isEqualTo("HELLO");
    }

    @Test
    void filterOperatorDropsNonMatching() {
        var outputs = new ArrayList<StreamEvent>();
        var pipeline = new StreamPipeline(List.of(
                new Operator.Filter("even", e -> ((int) e.value()) % 2 == 0),
                new Operator.Sink("out", outputs::add)
        ), 0);

        pipeline.process(event("k", 1, 1000));
        pipeline.process(event("k", 2, 2000));
        pipeline.process(event("k", 3, 3000));

        assertThat(outputs).hasSize(1);
        assertThat(outputs.getFirst().value()).isEqualTo(2);
    }

    @Test
    void flatMapOperatorExpandsEvents() {
        var outputs = new ArrayList<StreamEvent>();
        var pipeline = new StreamPipeline(List.of(
                new Operator.FlatMap("split", e -> {
                    String[] words = e.value().toString().split(" ");
                    return java.util.Arrays.stream(words)
                            .map(w -> new StreamEvent(e.key(), w, e.eventTime()))
                            .toList();
                }),
                new Operator.Sink("out", outputs::add)
        ), 0);

        pipeline.process(event("k", "a b c", 1000));
        assertThat(outputs).hasSize(3);
        assertThat(outputs.stream().map(e -> e.value().toString()).toList())
                .containsExactly("a", "b", "c");
    }

    @Test
    void tumblingWindowGroupsEvents() {
        var pipeline = new StreamPipeline(List.of(
                new Operator.Window("w1", new WindowStrategy.Tumbling(Duration.ofSeconds(10)))
        ), 0);

        long base = 1_700_000_000_000L;
        // 3 events in window [0, 10s)
        pipeline.process(event("k", "a", base + 1000));
        pipeline.process(event("k", "b", base + 5000));
        pipeline.process(event("k", "c", base + 9000));
        // 1 event in window [10s, 20s) — advances watermark past first window
        pipeline.process(event("k", "d", base + 11000));

        var results = pipeline.triggerWindows();
        assertThat(results).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    void sessionWindowGroupsByGap() {
        var pipeline = new StreamPipeline(List.of(
                new Operator.Window("w1", new WindowStrategy.Session(Duration.ofSeconds(5)))
        ), 0);

        long base = 1_700_000_000_000L;
        // Session 1: events at 0s, 2s, 4s (within 5s gap)
        pipeline.process(event("k", "a", base));
        pipeline.process(event("k", "b", base + 2000));
        pipeline.process(event("k", "c", base + 4000));
        // Gap > 5s
        // Session 2: event at 15s (new session, advances watermark past session 1)
        pipeline.process(event("k", "d", base + 15000));

        var results = pipeline.triggerWindows();
        // Session 1 should be closed (watermark passed its end)
        assertThat(results).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    void lateEventsAreDropped() {
        var outputs = new ArrayList<StreamEvent>();
        var pipeline = new StreamPipeline(List.of(
                new Operator.Sink("out", outputs::add)
        ), 0); // maxLateness = 0

        long base = 1_700_000_000_000L;
        pipeline.process(event("k", "new", base + 10000));
        // Late event (timestamp before watermark)
        pipeline.process(event("k", "late", base + 1000));

        assertThat(outputs).hasSize(1);
        assertThat(pipeline.droppedLateCount()).isEqualTo(1);
    }

    @Test
    void maxLatenessAllowsSlightlyLateEvents() {
        var outputs = new ArrayList<StreamEvent>();
        var pipeline = new StreamPipeline(List.of(
                new Operator.Sink("out", outputs::add)
        ), 5000); // 5s max lateness

        long base = 1_700_000_000_000L;
        pipeline.process(event("k", "new", base + 10000));
        // 3s late — within 5s tolerance (watermark = 10000 - 5000 = 5000)
        pipeline.process(event("k", "slightly-late", base + 7000));

        assertThat(outputs).hasSize(2);
        assertThat(pipeline.droppedLateCount()).isZero();
    }

    @Test
    void checkpointCapturesState() {
        var pipeline = new StreamPipeline(List.of(
                new Operator.Window("w1", new WindowStrategy.Tumbling(Duration.ofSeconds(10)))
        ), 0);

        pipeline.process(event("k", "a", 1_700_000_001_000L));

        var barrier = pipeline.checkpoint();
        assertThat(barrier.id()).isEqualTo(1);
        assertThat(barrier.snapshot()).containsKey("watermark");
        assertThat(barrier.snapshot()).containsKey("processedCount");
        assertThat(barrier.snapshot().get("processedCount")).isEqualTo(1L);
    }

    @Test
    void latestCheckpointTracking() {
        var pipeline = new StreamPipeline(List.of(), 0);
        assertThat(pipeline.latestCheckpoint()).isEmpty();

        pipeline.checkpoint();
        pipeline.checkpoint();

        var latest = pipeline.latestCheckpoint().orElseThrow();
        assertThat(latest.id()).isEqualTo(2);
    }

    @Test
    void processedCountTracking() {
        var pipeline = new StreamPipeline(List.of(), 0);
        pipeline.process(event("k", "a", 1000));
        pipeline.process(event("k", "b", 2000));
        assertThat(pipeline.processedCount()).isEqualTo(2);
    }

    @Test
    void watermarkAdvancesMonotonically() {
        var pipeline = new StreamPipeline(List.of(), 0);
        pipeline.process(event("k", "a", 5000));
        pipeline.process(event("k", "b", 3000)); // older timestamp
        pipeline.process(event("k", "c", 8000));

        // Watermark should be at 8000, not regressed to 3000
        assertThat(pipeline.currentWatermark()).isEqualTo(Instant.ofEpochMilli(8000));
    }

    @Test
    void chainedOperators() {
        var outputs = new ArrayList<StreamEvent>();
        var pipeline = new StreamPipeline(List.of(
                new Operator.Filter("positive", e -> ((int) e.value()) > 0),
                new Operator.Map("double", e ->
                        new StreamEvent(e.key(), (int) e.value() * 2, e.eventTime())),
                new Operator.Sink("out", outputs::add)
        ), 0);

        pipeline.process(event("k", -1, 1000));
        pipeline.process(event("k", 5, 2000));
        pipeline.process(event("k", 3, 3000));

        assertThat(outputs).hasSize(2);
        assertThat(outputs.get(0).value()).isEqualTo(10);
        assertThat(outputs.get(1).value()).isEqualTo(6);
    }

    @Test
    void emptyPipelinePassesThrough() {
        var pipeline = new StreamPipeline(List.of(), 0);
        var result = pipeline.process(event("k", "v", 1000));
        assertThat(result).hasSize(1);
    }

    // --- helpers ---

    private static StreamEvent event(String key, Object value, long epochMs) {
        return new StreamEvent(key, value, Instant.ofEpochMilli(epochMs));
    }
}
