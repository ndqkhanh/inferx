package com.inferx.stream;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class WindowStrategyTest {

    @Test
    void tumblingWindowCreation() {
        var w = new WindowStrategy.Tumbling(Duration.ofMinutes(1));
        assertThat(w.size()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void tumblingRejectsZeroDuration() {
        assertThatThrownBy(() -> new WindowStrategy.Tumbling(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tumblingRejectsNegativeDuration() {
        assertThatThrownBy(() -> new WindowStrategy.Tumbling(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void slidingWindowCreation() {
        var w = new WindowStrategy.Sliding(Duration.ofMinutes(5), Duration.ofMinutes(1));
        assertThat(w.size()).isEqualTo(Duration.ofMinutes(5));
        assertThat(w.slide()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void slidingRejectsInvalidDurations() {
        assertThatThrownBy(() -> new WindowStrategy.Sliding(Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WindowStrategy.Sliding(Duration.ofSeconds(5), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sessionWindowCreation() {
        var w = new WindowStrategy.Session(Duration.ofMinutes(5));
        assertThat(w.gap()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void sessionRejectsInvalidGap() {
        assertThatThrownBy(() -> new WindowStrategy.Session(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sealedExhaustiveSwitch() {
        WindowStrategy strategy = new WindowStrategy.Tumbling(Duration.ofSeconds(10));
        String type = switch (strategy) {
            case WindowStrategy.Tumbling t -> "tumbling:" + t.size().getSeconds();
            case WindowStrategy.Sliding s -> "sliding:" + s.size().getSeconds();
            case WindowStrategy.Session s -> "session:" + s.gap().getSeconds();
        };
        assertThat(type).isEqualTo("tumbling:10");
    }
}
