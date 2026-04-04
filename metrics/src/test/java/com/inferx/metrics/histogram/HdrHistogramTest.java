package com.inferx.metrics.histogram;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.*;

class HdrHistogramTest {

    @Test
    void emptyHistogramReturnsZeros() {
        var hist = new HdrHistogram(1_000_000, 2);
        assertThat(hist.totalCount()).isZero();
        assertThat(hist.mean()).isEqualTo(0.0);
        assertThat(hist.min()).isZero();
        assertThat(hist.max()).isZero();
        assertThat(hist.getValueAtPercentile(50)).isZero();
    }

    @Test
    void singleValue() {
        var hist = new HdrHistogram(1_000_000, 2);
        hist.recordValue(42);
        assertThat(hist.totalCount()).isEqualTo(1);
        assertThat(hist.min()).isEqualTo(42);
        assertThat(hist.max()).isEqualTo(42);
        assertThat(hist.getValueAtPercentile(50)).isEqualTo(42);
        assertThat(hist.getValueAtPercentile(99)).isEqualTo(42);
    }

    @Test
    void percentileCalculation() {
        var hist = new HdrHistogram(1_000_000, 2);
        // Record values 1-100
        for (int i = 1; i <= 100; i++) {
            hist.recordValue(i);
        }
        assertThat(hist.totalCount()).isEqualTo(100);
        assertThat(hist.getValueAtPercentile(50)).isBetween(49L, 51L);
        assertThat(hist.getValueAtPercentile(90)).isBetween(89L, 91L);
        assertThat(hist.getValueAtPercentile(99)).isBetween(98L, 100L);
        assertThat(hist.getValueAtPercentile(100)).isEqualTo(100L);
    }

    @Test
    void meanCalculation() {
        var hist = new HdrHistogram(1_000_000, 2);
        hist.recordValue(10);
        hist.recordValue(20);
        hist.recordValue(30);
        assertThat(hist.mean()).isBetween(19.0, 21.0);
    }

    @Test
    void largeValueRange() {
        var hist = new HdrHistogram(3_600_000, 2); // up to 1 hour in ms
        hist.recordValue(1);         // 1ms
        hist.recordValue(100);       // 100ms
        hist.recordValue(1000);      // 1s
        hist.recordValue(60_000);    // 1min
        hist.recordValue(3_600_000); // 1hr

        assertThat(hist.totalCount()).isEqualTo(5);
        assertThat(hist.min()).isEqualTo(1);
    }

    @Test
    void recordValueWithCount() {
        var hist = new HdrHistogram(1_000, 2);
        hist.recordValueWithCount(50, 1000);
        assertThat(hist.totalCount()).isEqualTo(1000);
        assertThat(hist.getValueAtPercentile(50)).isEqualTo(50);
    }

    @Test
    void resetClearsAllData() {
        var hist = new HdrHistogram(1_000, 2);
        for (int i = 0; i < 100; i++) hist.recordValue(i);
        assertThat(hist.totalCount()).isEqualTo(100);
        hist.reset();
        assertThat(hist.totalCount()).isZero();
        assertThat(hist.max()).isZero();
    }

    @Test
    void rejectsNegativeValues() {
        var hist = new HdrHistogram(1_000, 2);
        assertThatThrownBy(() -> hist.recordValue(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidSignificantDigits() {
        assertThatThrownBy(() -> new HdrHistogram(1000, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HdrHistogram(1000, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void threadSafety() throws InterruptedException {
        var hist = new HdrHistogram(1_000_000, 2);
        int threads = 8;
        int perThread = 10_000;
        var latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            Thread.startVirtualThread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        hist.recordValue(ThreadLocalRandom.current().nextLong(1, 100_000));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        assertThat(hist.totalCount()).isEqualTo((long) threads * perThread);
    }

    @Test
    void typicalLatencyDistribution() {
        var hist = new HdrHistogram(10_000, 2); // max 10s in ms
        // Simulate typical API latency: most <100ms, some slow
        var rng = ThreadLocalRandom.current();
        for (int i = 0; i < 10_000; i++) {
            long latency = (long) (rng.nextGaussian() * 20 + 50); // mean 50ms, stddev 20ms
            if (latency < 1) latency = 1;
            hist.recordValue(latency);
        }
        // Add a few outliers
        for (int i = 0; i < 100; i++) {
            hist.recordValue(rng.nextLong(500, 5000));
        }

        assertThat(hist.getValueAtPercentile(50)).isBetween(30L, 70L);
        assertThat(hist.getValueAtPercentile(99)).isGreaterThan(80L);
    }
}
