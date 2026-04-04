package com.inferx.stream;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class WatermarkTest {

    @Test
    void startsAtEpoch() {
        var wm = new Watermark(0);
        assertThat(wm.current()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void advancesWithEventTime() {
        var wm = new Watermark(0);
        wm.advance(Instant.ofEpochMilli(5000));
        assertThat(wm.current()).isEqualTo(Instant.ofEpochMilli(5000));
    }

    @Test
    void neverGoesBackwards() {
        var wm = new Watermark(0);
        wm.advance(Instant.ofEpochMilli(5000));
        wm.advance(Instant.ofEpochMilli(3000));
        assertThat(wm.current()).isEqualTo(Instant.ofEpochMilli(5000));
    }

    @Test
    void maxLatenessSubtracted() {
        var wm = new Watermark(2000); // 2s lateness
        wm.advance(Instant.ofEpochMilli(10000));
        // Watermark = 10000 - 2000 = 8000
        assertThat(wm.current()).isEqualTo(Instant.ofEpochMilli(8000));
    }

    @Test
    void lateDetection() {
        var wm = new Watermark(0);
        wm.advance(Instant.ofEpochMilli(5000));
        assertThat(wm.isLate(Instant.ofEpochMilli(3000))).isTrue();
        assertThat(wm.isLate(Instant.ofEpochMilli(5000))).isFalse();
        assertThat(wm.isLate(Instant.ofEpochMilli(7000))).isFalse();
    }

    @Test
    void windowTrigger() {
        var wm = new Watermark(0);
        var windowEnd = Instant.ofEpochMilli(10000);

        assertThat(wm.shouldTriggerWindow(windowEnd)).isFalse();

        wm.advance(Instant.ofEpochMilli(9000));
        assertThat(wm.shouldTriggerWindow(windowEnd)).isFalse();

        wm.advance(Instant.ofEpochMilli(10000));
        assertThat(wm.shouldTriggerWindow(windowEnd)).isTrue();

        wm.advance(Instant.ofEpochMilli(12000));
        assertThat(wm.shouldTriggerWindow(windowEnd)).isTrue();
    }

    @Test
    void rejectsNegativeLateness() {
        assertThatThrownBy(() -> new Watermark(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void threadSafeAdvancement() throws InterruptedException {
        var wm = new Watermark(0);
        int threads = 8;
        var latch = new java.util.concurrent.CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int offset = t;
            Thread.startVirtualThread(() -> {
                try {
                    for (int i = 0; i < 1000; i++) {
                        wm.advance(Instant.ofEpochMilli(offset * 1000L + i));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        // Should be at the max value any thread produced
        assertThat(wm.current().toEpochMilli()).isEqualTo(7999);
    }
}
