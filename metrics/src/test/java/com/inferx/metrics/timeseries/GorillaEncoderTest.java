package com.inferx.metrics.timeseries;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class GorillaEncoderTest {

    @Test
    void singlePointStoresRaw() {
        var encoder = new GorillaEncoder(1024);
        encoder.encode(1000L, 42.0);
        assertThat(encoder.count()).isEqualTo(1);
        assertThat(encoder.compressedSizeBytes()).isEqualTo(16); // raw: 8+8 bytes
    }

    @Test
    void identicalValuesCompressWell() {
        var encoder = new GorillaEncoder(4096);
        long base = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            encoder.encode(base + i * 10_000, 99.5);
        }
        assertThat(encoder.count()).isEqualTo(100);
        // 100 points * 16 bytes = 1600 raw, should compress significantly
        assertThat(encoder.compressionRatio()).isGreaterThan(5.0);
    }

    @Test
    void regularIntervalsCompressTimestamps() {
        var encoder = new GorillaEncoder(4096);
        long base = 1_700_000_000_000L;
        for (int i = 0; i < 50; i++) {
            encoder.encode(base + i * 60_000, 100.0 + i * 0.1); // 1-min intervals
        }
        assertThat(encoder.count()).isEqualTo(50);
        // Timestamps compress well (regular delta), values compress moderately (small changes)
        assertThat(encoder.compressionRatio()).isGreaterThan(1.5);
    }

    @Test
    void varyingValuesStillCompress() {
        var encoder = new GorillaEncoder(8192);
        long base = System.currentTimeMillis();
        for (int i = 0; i < 200; i++) {
            double value = 50.0 + Math.sin(i * 0.1) * 10; // sinusoidal
            encoder.encode(base + i * 1000, value);
        }
        assertThat(encoder.count()).isEqualTo(200);
        // Even varying values should compress somewhat due to XOR similarity
        assertThat(encoder.compressionRatio()).isGreaterThan(2.0);
    }

    @Test
    void toByteArrayReturnsCorrectSize() {
        var encoder = new GorillaEncoder(1024);
        encoder.encode(1000L, 1.0);
        encoder.encode(2000L, 2.0);
        byte[] data = encoder.toByteArray();
        assertThat(data.length).isEqualTo(encoder.compressedSizeBytes());
    }

    @Test
    void zigzagEncodeDecode() {
        assertThat(GorillaEncoder.zigzagDecode(GorillaEncoder.zigzagEncode(0))).isEqualTo(0);
        assertThat(GorillaEncoder.zigzagDecode(GorillaEncoder.zigzagEncode(1))).isEqualTo(1);
        assertThat(GorillaEncoder.zigzagDecode(GorillaEncoder.zigzagEncode(-1))).isEqualTo(-1);
        assertThat(GorillaEncoder.zigzagDecode(GorillaEncoder.zigzagEncode(100))).isEqualTo(100);
        assertThat(GorillaEncoder.zigzagDecode(GorillaEncoder.zigzagEncode(-100))).isEqualTo(-100);
        assertThat(GorillaEncoder.zigzagDecode(GorillaEncoder.zigzagEncode(Long.MAX_VALUE))).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void emptyEncoderHasZeroRatio() {
        var encoder = new GorillaEncoder(64);
        assertThat(encoder.compressionRatio()).isEqualTo(0.0);
        assertThat(encoder.count()).isEqualTo(0);
    }

    @Test
    void highCompressionWithConstantMetrics() {
        // Simulating a steady-state server metric (e.g., CPU at 45.2%)
        var encoder = new GorillaEncoder(8192);
        long base = 1_700_000_000_000L;
        for (int i = 0; i < 360; i++) { // 6 hours at 1-min intervals
            encoder.encode(base + i * 60_000L, 45.2);
        }
        assertThat(encoder.count()).isEqualTo(360);
        // Constant value + regular interval = extreme compression
        assertThat(encoder.compressionRatio()).isGreaterThan(10.0);
    }
}
