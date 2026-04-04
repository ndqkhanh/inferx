package com.inferx.stream;

import java.time.Duration;

/**
 * Windowing strategies for grouping stream events.
 * Sealed to ensure exhaustive handling.
 */
public sealed interface WindowStrategy
        permits WindowStrategy.Tumbling, WindowStrategy.Sliding, WindowStrategy.Session {

    /**
     * Fixed-size, non-overlapping windows.
     * Example: 1-minute tumbling window groups all events in each 1-minute interval.
     */
    record Tumbling(Duration size) implements WindowStrategy {
        public Tumbling { if (size.isNegative() || size.isZero()) throw new IllegalArgumentException("Window size must be positive"); }
    }

    /**
     * Fixed-size, overlapping windows that slide by a given interval.
     * Example: 5-minute window sliding every 1 minute produces 5 overlapping windows.
     */
    record Sliding(Duration size, Duration slide) implements WindowStrategy {
        public Sliding {
            if (size.isNegative() || size.isZero()) throw new IllegalArgumentException("Window size must be positive");
            if (slide.isNegative() || slide.isZero()) throw new IllegalArgumentException("Slide must be positive");
        }
    }

    /**
     * Dynamic windows that close after a gap of inactivity.
     * Example: session window with 5-minute gap groups all events until
     * there's a 5-minute pause, then starts a new window.
     */
    record Session(Duration gap) implements WindowStrategy {
        public Session { if (gap.isNegative() || gap.isZero()) throw new IllegalArgumentException("Gap must be positive"); }
    }
}
