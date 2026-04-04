package com.inferx.metrics.timeseries;

import java.nio.ByteBuffer;

/**
 * Gorilla-style time-series compression (Pelkonen et al. 2015, Facebook).
 * Uses delta-of-delta for timestamps and XOR + leading/trailing zero compression for values.
 * Achieves ~12x compression vs raw doubles for typical metrics data.
 */
public final class GorillaEncoder {

    private final ByteBuffer buffer;
    private int bitPos;

    private long prevTimestamp;
    private long prevDelta;
    private long prevValue;
    private int count;

    public GorillaEncoder(int capacityBytes) {
        this.buffer = ByteBuffer.allocate(capacityBytes);
        this.bitPos = 0;
        this.count = 0;
    }

    /**
     * Encode a (timestamp, value) pair.
     * First point is stored raw; subsequent points use delta-of-delta + XOR compression.
     */
    public void encode(long timestampMs, double value) {
        long valueBits = Double.doubleToRawLongBits(value);

        if (count == 0) {
            // First point: store raw timestamp (64 bits) and value (64 bits)
            writeBits(timestampMs, 64);
            writeBits(valueBits, 64);
            prevTimestamp = timestampMs;
            prevValue = valueBits;
            prevDelta = 0;
        } else {
            encodeTimestamp(timestampMs);
            encodeValue(valueBits);
        }
        count++;
    }

    /**
     * Delta-of-delta encoding for timestamps.
     * Most timestamps have regular intervals, so delta-of-delta is often 0.
     */
    private void encodeTimestamp(long timestamp) {
        long delta = timestamp - prevTimestamp;
        long deltaOfDelta = delta - prevDelta;

        if (deltaOfDelta == 0) {
            // '0' — single bit
            writeBit(0);
        } else {
            long encoded = zigzagEncode(deltaOfDelta);
            if (encoded >= -63 && encoded <= 64) {
                // '10' + 7 bits
                writeBit(1);
                writeBit(0);
                writeBits(zigzagEncode(deltaOfDelta), 7);
            } else if (encoded >= -255 && encoded <= 256) {
                // '110' + 9 bits
                writeBit(1);
                writeBit(1);
                writeBit(0);
                writeBits(zigzagEncode(deltaOfDelta), 9);
            } else if (encoded >= -2047 && encoded <= 2048) {
                // '1110' + 12 bits
                writeBit(1);
                writeBit(1);
                writeBit(1);
                writeBit(0);
                writeBits(zigzagEncode(deltaOfDelta), 12);
            } else {
                // '1111' + 64 bits (full delta)
                writeBit(1);
                writeBit(1);
                writeBit(1);
                writeBit(1);
                writeBits(delta, 64);
            }
        }

        prevDelta = delta;
        prevTimestamp = timestamp;
    }

    /**
     * XOR-based value encoding.
     * Consecutive values of the same metric are often very similar,
     * so XOR produces values with many leading and trailing zeros.
     */
    private void encodeValue(long valueBits) {
        long xor = prevValue ^ valueBits;

        if (xor == 0) {
            // Values identical — single '0' bit
            writeBit(0);
        } else {
            writeBit(1);
            int leadingZeros = Long.numberOfLeadingZeros(xor);
            int trailingZeros = Long.numberOfTrailingZeros(xor);
            int significantBits = 64 - leadingZeros - trailingZeros;

            // '1' + 5 bits leading zeros + 6 bits significant length + significant bits
            writeBits(leadingZeros, 5);
            writeBits(significantBits, 6);
            writeBits(xor >>> trailingZeros, significantBits);
        }

        prevValue = valueBits;
    }

    /** Zigzag encoding maps signed integers to unsigned: 0→0, -1→1, 1→2, -2→3, ... */
    static long zigzagEncode(long n) {
        return (n << 1) ^ (n >> 63);
    }

    /** Inverse of zigzag encoding. */
    static long zigzagDecode(long n) {
        return (n >>> 1) ^ -(n & 1);
    }

    private void writeBit(int bit) {
        int byteIndex = bitPos / 8;
        int bitIndex = 7 - (bitPos % 8);
        if (byteIndex >= buffer.capacity()) {
            throw new IllegalStateException("Buffer overflow at bit " + bitPos);
        }
        if (bit == 1) {
            buffer.put(byteIndex, (byte) (buffer.get(byteIndex) | (1 << bitIndex)));
        }
        bitPos++;
    }

    private void writeBits(long value, int numBits) {
        for (int i = numBits - 1; i >= 0; i--) {
            writeBit((int) ((value >>> i) & 1));
        }
    }

    /** Returns the number of data points encoded. */
    public int count() { return count; }

    /** Returns the compressed size in bytes. */
    public int compressedSizeBytes() {
        return (bitPos + 7) / 8;
    }

    /** Returns the raw (uncompressed) size in bytes (16 bytes per point). */
    public int rawSizeBytes() {
        return count * 16; // 8 bytes timestamp + 8 bytes value
    }

    /** Returns the compression ratio (raw / compressed). */
    public double compressionRatio() {
        int compressed = compressedSizeBytes();
        return compressed == 0 ? 0.0 : (double) rawSizeBytes() / compressed;
    }

    /** Returns a copy of the compressed data. */
    public byte[] toByteArray() {
        int size = compressedSizeBytes();
        byte[] result = new byte[size];
        buffer.rewind();
        buffer.get(result, 0, size);
        return result;
    }
}
