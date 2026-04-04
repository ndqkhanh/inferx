package com.inferx.common.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * An event flowing through the stream processing engine.
 * Carries both event-time (when it happened) and processing-time (when ingested).
 *
 * @param key            partition/group key for windowing
 * @param value          the event payload
 * @param eventTime      when the event actually occurred (source timestamp)
 * @param processingTime when the event entered the stream engine
 * @param attributes     additional key-value metadata
 */
public record StreamEvent(
        String key,
        Object value,
        Instant eventTime,
        Instant processingTime,
        Map<String, String> attributes
) {
    public StreamEvent {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(eventTime, "eventTime");
        if (processingTime == null) processingTime = Instant.now();
        attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
    }

    /** Convenience constructor with event time only. */
    public StreamEvent(String key, Object value, Instant eventTime) {
        this(key, value, eventTime, Instant.now(), Map.of());
    }

    /** How far behind processing-time is from event-time (lag). */
    public long lagMs() {
        return processingTime.toEpochMilli() - eventTime.toEpochMilli();
    }
}
