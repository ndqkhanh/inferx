package com.inferx.common.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class StreamEventTest {

    @Test
    void basicCreation() {
        var now = Instant.now();
        var event = new StreamEvent("user-123", "click", now);
        assertThat(event.key()).isEqualTo("user-123");
        assertThat(event.value()).isEqualTo("click");
        assertThat(event.eventTime()).isEqualTo(now);
        assertThat(event.processingTime()).isAfterOrEqualTo(now);
        assertThat(event.attributes()).isEmpty();
    }

    @Test
    void fullCreation() {
        var eventTime = Instant.now().minusSeconds(5);
        var processTime = Instant.now();
        var attrs = Map.of("source", "kafka");
        var event = new StreamEvent("k", "v", eventTime, processTime, attrs);
        assertThat(event.attributes()).containsEntry("source", "kafka");
    }

    @Test
    void lagCalculation() {
        var eventTime = Instant.now().minusSeconds(2);
        var processTime = Instant.now();
        var event = new StreamEvent("k", "v", eventTime, processTime, Map.of());
        assertThat(event.lagMs()).isBetween(1900L, 2100L);
    }

    @Test
    void rejectsNullKey() {
        assertThatThrownBy(() -> new StreamEvent(null, "v", Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullValue() {
        assertThatThrownBy(() -> new StreamEvent("k", null, Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void attributesAreImmutable() {
        var attrs = new java.util.HashMap<String, String>();
        attrs.put("a", "b");
        var event = new StreamEvent("k", "v", Instant.now(), Instant.now(), attrs);
        assertThatThrownBy(() -> event.attributes().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
