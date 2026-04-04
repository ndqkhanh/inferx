package com.inferx.protocol;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * HTTP/2 binary frame representation (RFC 7540).
 * Hand-built frame parser/serializer without any HTTP library dependency.
 *
 * Frame layout (9-byte header + payload):
 * +-----------------------------------------------+
 * |                Length (24)                     |
 * +---------------+---------------+---------------+
 * |  Type (8)     |  Flags (8)    |
 * +-+-------------+---------------+------+--------+
 * |R|             Stream Identifier (31)          |
 * +=+=============+==============================+
 * |                Frame Payload                  |
 * +-----------------------------------------------+
 */
public record HttpFrame(int type, int flags, int streamId, byte[] payload) {

    public static final int HEADER_SIZE = 9;

    // Frame types
    public static final int TYPE_DATA = 0x0;
    public static final int TYPE_HEADERS = 0x1;
    public static final int TYPE_PRIORITY = 0x2;
    public static final int TYPE_RST_STREAM = 0x3;
    public static final int TYPE_SETTINGS = 0x4;
    public static final int TYPE_PUSH_PROMISE = 0x5;
    public static final int TYPE_PING = 0x6;
    public static final int TYPE_GOAWAY = 0x7;
    public static final int TYPE_WINDOW_UPDATE = 0x8;

    // Common flags
    public static final int FLAG_END_STREAM = 0x1;
    public static final int FLAG_END_HEADERS = 0x4;
    public static final int FLAG_PADDED = 0x8;

    public HttpFrame {
        Objects.requireNonNull(payload, "payload");
        if (streamId < 0) throw new IllegalArgumentException("streamId must be >= 0");
        if (payload.length > 0xFFFFFF) throw new IllegalArgumentException("Payload too large: " + payload.length);
    }

    /** Serialize this frame to a byte buffer. */
    public byte[] serialize() {
        byte[] result = new byte[HEADER_SIZE + payload.length];
        // Length (24 bits, big-endian)
        result[0] = (byte) ((payload.length >> 16) & 0xFF);
        result[1] = (byte) ((payload.length >> 8) & 0xFF);
        result[2] = (byte) (payload.length & 0xFF);
        // Type (8 bits)
        result[3] = (byte) type;
        // Flags (8 bits)
        result[4] = (byte) flags;
        // Stream ID (31 bits, MSB reserved)
        result[5] = (byte) ((streamId >> 24) & 0x7F);
        result[6] = (byte) ((streamId >> 16) & 0xFF);
        result[7] = (byte) ((streamId >> 8) & 0xFF);
        result[8] = (byte) (streamId & 0xFF);
        // Payload
        System.arraycopy(payload, 0, result, HEADER_SIZE, payload.length);
        return result;
    }

    /** Parse a frame from a ByteBuffer (must have at least HEADER_SIZE + length bytes). */
    public static HttpFrame parse(ByteBuffer buffer) {
        if (buffer.remaining() < HEADER_SIZE) {
            throw new IllegalArgumentException("Not enough data for frame header");
        }

        int length = ((buffer.get() & 0xFF) << 16) |
                     ((buffer.get() & 0xFF) << 8) |
                     (buffer.get() & 0xFF);
        int type = buffer.get() & 0xFF;
        int flags = buffer.get() & 0xFF;
        int streamId = ((buffer.get() & 0x7F) << 24) |
                       ((buffer.get() & 0xFF) << 16) |
                       ((buffer.get() & 0xFF) << 8) |
                       (buffer.get() & 0xFF);

        if (buffer.remaining() < length) {
            throw new IllegalArgumentException("Not enough data for payload: need " + length + ", have " + buffer.remaining());
        }

        byte[] payload = new byte[length];
        buffer.get(payload);

        return new HttpFrame(type, flags, streamId, payload);
    }

    public boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }

    public boolean isEndStream() { return hasFlag(FLAG_END_STREAM); }
    public boolean isEndHeaders() { return hasFlag(FLAG_END_HEADERS); }

    /** Create a DATA frame. */
    public static HttpFrame data(int streamId, byte[] data, boolean endStream) {
        return new HttpFrame(TYPE_DATA, endStream ? FLAG_END_STREAM : 0, streamId, data);
    }

    /** Create a SETTINGS frame. */
    public static HttpFrame settings(byte[] settings) {
        return new HttpFrame(TYPE_SETTINGS, 0, 0, settings);
    }

    /** Create a PING frame. */
    public static HttpFrame ping(byte[] opaqueData) {
        if (opaqueData.length != 8) throw new IllegalArgumentException("PING payload must be 8 bytes");
        return new HttpFrame(TYPE_PING, 0, 0, opaqueData);
    }

    /** Create a GOAWAY frame. */
    public static HttpFrame goaway(int lastStreamId, int errorCode) {
        byte[] payload = new byte[8];
        payload[0] = (byte) ((lastStreamId >> 24) & 0x7F);
        payload[1] = (byte) ((lastStreamId >> 16) & 0xFF);
        payload[2] = (byte) ((lastStreamId >> 8) & 0xFF);
        payload[3] = (byte) (lastStreamId & 0xFF);
        payload[4] = (byte) ((errorCode >> 24) & 0xFF);
        payload[5] = (byte) ((errorCode >> 16) & 0xFF);
        payload[6] = (byte) ((errorCode >> 8) & 0xFF);
        payload[7] = (byte) (errorCode & 0xFF);
        return new HttpFrame(TYPE_GOAWAY, 0, 0, payload);
    }
}
