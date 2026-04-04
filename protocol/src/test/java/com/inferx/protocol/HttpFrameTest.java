package com.inferx.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.*;

class HttpFrameTest {

    @Test
    void serializeAndParseRoundTrip() {
        var original = HttpFrame.data(1, "hello".getBytes(), true);
        byte[] serialized = original.serialize();
        var parsed = HttpFrame.parse(ByteBuffer.wrap(serialized));

        assertThat(parsed.type()).isEqualTo(HttpFrame.TYPE_DATA);
        assertThat(parsed.streamId()).isEqualTo(1);
        assertThat(parsed.payload()).isEqualTo("hello".getBytes());
        assertThat(parsed.isEndStream()).isTrue();
    }

    @Test
    void settingsFrame() {
        var frame = HttpFrame.settings(new byte[]{0, 1, 0, 0, 0x10, 0x00});
        assertThat(frame.type()).isEqualTo(HttpFrame.TYPE_SETTINGS);
        assertThat(frame.streamId()).isEqualTo(0);

        byte[] serialized = frame.serialize();
        var parsed = HttpFrame.parse(ByteBuffer.wrap(serialized));
        assertThat(parsed.type()).isEqualTo(HttpFrame.TYPE_SETTINGS);
    }

    @Test
    void pingFrame() {
        byte[] opaqueData = {1, 2, 3, 4, 5, 6, 7, 8};
        var frame = HttpFrame.ping(opaqueData);
        assertThat(frame.type()).isEqualTo(HttpFrame.TYPE_PING);
        assertThat(frame.payload()).isEqualTo(opaqueData);
    }

    @Test
    void pingRejectsWrongSize() {
        assertThatThrownBy(() -> HttpFrame.ping(new byte[7]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void goawayFrame() {
        var frame = HttpFrame.goaway(5, 0);
        assertThat(frame.type()).isEqualTo(HttpFrame.TYPE_GOAWAY);
        assertThat(frame.streamId()).isEqualTo(0);

        byte[] serialized = frame.serialize();
        var parsed = HttpFrame.parse(ByteBuffer.wrap(serialized));
        assertThat(parsed.payload()).hasSize(8);
    }

    @Test
    void flagChecking() {
        var frame = new HttpFrame(HttpFrame.TYPE_DATA,
                HttpFrame.FLAG_END_STREAM | HttpFrame.FLAG_PADDED,
                1, new byte[0]);
        assertThat(frame.isEndStream()).isTrue();
        assertThat(frame.hasFlag(HttpFrame.FLAG_PADDED)).isTrue();
        assertThat(frame.isEndHeaders()).isFalse();
    }

    @Test
    void headerSizeConstant() {
        assertThat(HttpFrame.HEADER_SIZE).isEqualTo(9);
    }

    @Test
    void emptyPayloadRoundTrip() {
        var frame = new HttpFrame(HttpFrame.TYPE_HEADERS, HttpFrame.FLAG_END_HEADERS, 3, new byte[0]);
        byte[] serialized = frame.serialize();
        var parsed = HttpFrame.parse(ByteBuffer.wrap(serialized));
        assertThat(parsed.payload()).isEmpty();
        assertThat(parsed.streamId()).isEqualTo(3);
    }

    @Test
    void rejectsNegativeStreamId() {
        assertThatThrownBy(() -> new HttpFrame(0, 0, -1, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsInsufficientData() {
        assertThatThrownBy(() -> HttpFrame.parse(ByteBuffer.wrap(new byte[5])))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void largePayloadRoundTrip() {
        byte[] payload = new byte[65535];
        java.util.Arrays.fill(payload, (byte) 0xAB);
        var frame = HttpFrame.data(7, payload, false);
        byte[] serialized = frame.serialize();
        var parsed = HttpFrame.parse(ByteBuffer.wrap(serialized));
        assertThat(parsed.payload()).hasSize(65535);
        assertThat(parsed.payload()[0]).isEqualTo((byte) 0xAB);
    }

    @Test
    void multipleFramesFromBuffer() {
        var f1 = HttpFrame.data(1, "abc".getBytes(), false);
        var f2 = HttpFrame.data(3, "xyz".getBytes(), true);

        byte[] combined = new byte[f1.serialize().length + f2.serialize().length];
        System.arraycopy(f1.serialize(), 0, combined, 0, f1.serialize().length);
        System.arraycopy(f2.serialize(), 0, combined, f1.serialize().length, f2.serialize().length);

        var buf = ByteBuffer.wrap(combined);
        var parsed1 = HttpFrame.parse(buf);
        var parsed2 = HttpFrame.parse(buf);

        assertThat(parsed1.streamId()).isEqualTo(1);
        assertThat(parsed2.streamId()).isEqualTo(3);
        assertThat(parsed2.isEndStream()).isTrue();
    }
}
