package com.inferx.metrics.counter;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.*;

class MetricRegistryTest {

    @Test
    void counterIncrement() {
        var registry = new MetricRegistry();
        registry.increment("requests");
        registry.increment("requests");
        registry.increment("requests", 3);
        assertThat(registry.counterValue("requests")).isEqualTo(5);
    }

    @Test
    void unknownCounterReturnsZero() {
        var registry = new MetricRegistry();
        assertThat(registry.counterValue("nonexistent")).isZero();
    }

    @Test
    void gaugeSetAndRead() {
        var registry = new MetricRegistry();
        registry.gauge("active_connections", 42);
        assertThat(registry.gaugeValue("active_connections")).isEqualTo(42);
    }

    @Test
    void unknownGaugeReturnsZero() {
        var registry = new MetricRegistry();
        assertThat(registry.gaugeValue("nonexistent")).isZero();
    }

    @Test
    void histogramRecordAndQuery() {
        var registry = new MetricRegistry();
        for (int i = 1; i <= 100; i++) {
            registry.recordHistogram("latency_ms", i);
        }
        var hist = registry.histogram("latency_ms");
        assertThat(hist).isNotNull();
        assertThat(hist.totalCount()).isEqualTo(100);
        assertThat(hist.getValueAtPercentile(50)).isBetween(49L, 51L);
    }

    @Test
    void counterSnapshot() {
        var registry = new MetricRegistry();
        registry.increment("a", 10);
        registry.increment("b", 20);
        var snapshot = registry.counterSnapshot();
        assertThat(snapshot).containsEntry("a", 10L).containsEntry("b", 20L);
    }

    @Test
    void snapshotIsImmutable() {
        var registry = new MetricRegistry();
        registry.increment("x");
        var snapshot = registry.counterSnapshot();
        assertThatThrownBy(() -> snapshot.put("y", 1L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void resetClearsAll() {
        var registry = new MetricRegistry();
        registry.increment("count", 100);
        registry.gauge("gauge", 50);
        registry.recordHistogram("hist", 42);
        registry.reset();
        assertThat(registry.counterValue("count")).isZero();
        assertThat(registry.gaugeValue("gauge")).isZero();
        assertThat(registry.histogram("hist").totalCount()).isZero();
    }

    @Test
    void threadSafeCounterIncrements() throws InterruptedException {
        var registry = new MetricRegistry();
        int threads = 8;
        int perThread = 10_000;
        var latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            Thread.startVirtualThread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        registry.increment("concurrent");
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        assertThat(registry.counterValue("concurrent")).isEqualTo((long) threads * perThread);
    }
}
