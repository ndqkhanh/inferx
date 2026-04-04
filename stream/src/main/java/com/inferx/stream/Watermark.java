package com.inferx.stream;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Watermark tracker for event-time processing (Akidau et al. 2015, Google Dataflow).
 *
 * A watermark is an assertion that no more events with timestamp <= watermark
 * will arrive. It propagates through the operator DAG, enabling windows to
 * close and emit results even with out-of-order data.
 *
 * Thread-safe via AtomicReference with monotonic advancement.
 */
public final class Watermark {

    private final AtomicReference<Instant> current;
    private final long maxLatenessMs;

    /**
     * @param maxLatenessMs maximum allowed event lateness in milliseconds.
     *                      Events arriving more than this after the watermark are dropped.
     */
    public Watermark(long maxLatenessMs) {
        if (maxLatenessMs < 0) throw new IllegalArgumentException("maxLatenessMs must be >= 0");
        this.maxLatenessMs = maxLatenessMs;
        this.current = new AtomicReference<>(Instant.EPOCH);
    }

    /**
     * Advance the watermark based on observed event time.
     * Watermark = max(observed_event_time) - maxLateness.
     * Monotonically increasing — never goes backwards.
     */
    public void advance(Instant eventTime) {
        Instant proposed = eventTime.minusMillis(maxLatenessMs);
        current.updateAndGet(prev -> proposed.isAfter(prev) ? proposed : prev);
    }

    /** Get the current watermark value. */
    public Instant current() {
        return current.get();
    }

    /**
     * Check if an event is late (arrived after the watermark has passed its timestamp).
     * Late events may be dropped or sent to a side output.
     */
    public boolean isLate(Instant eventTime) {
        return eventTime.isBefore(current.get());
    }

    /**
     * Check if a window ending at windowEnd should be triggered (closed).
     * A window triggers when the watermark passes its end time.
     */
    public boolean shouldTriggerWindow(Instant windowEnd) {
        return !current.get().isBefore(windowEnd);
    }

    /** Get the configured max lateness. */
    public long maxLatenessMs() {
        return maxLatenessMs;
    }
}
