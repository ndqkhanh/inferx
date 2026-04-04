package com.inferx.scheduler;

import com.inferx.common.error.InferXException;
import com.inferx.common.model.InferRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class EdfSchedulerTest {

    @Test
    void earliestDeadlineServedFirst() {
        var scheduler = new EdfScheduler(100);
        var now = Instant.now();

        scheduler.submit(request("r-late", now.plusSeconds(30), 1));
        scheduler.submit(request("r-urgent", now.plusSeconds(5), 1));
        scheduler.submit(request("r-mid", now.plusSeconds(15), 1));

        var first = scheduler.pollNext().orElseThrow();
        var second = scheduler.pollNext().orElseThrow();
        var third = scheduler.pollNext().orElseThrow();

        assertThat(first.request().id()).isEqualTo("r-urgent");
        assertThat(second.request().id()).isEqualTo("r-mid");
        assertThat(third.request().id()).isEqualTo("r-late");
    }

    @Test
    void higherPriorityBreaksTie() {
        var scheduler = new EdfScheduler(100);
        var deadline = Instant.now().plusSeconds(60);

        scheduler.submit(request("r-low", deadline, 1));
        scheduler.submit(request("r-high", deadline, 10));

        var first = scheduler.pollNext().orElseThrow();
        assertThat(first.request().id()).isEqualTo("r-high");
    }

    @Test
    void rejectsAlreadyExpiredDeadline() {
        var scheduler = new EdfScheduler(100);
        var past = Instant.now().minusSeconds(1);

        assertThatThrownBy(() -> scheduler.submit(request("r-expired", past, 1)))
                .isInstanceOf(InferXException.DeadlineMissed.class);
        assertThat(scheduler.totalDeadlineMisses()).isEqualTo(1);
    }

    @Test
    void rejectsWhenQueueFull() {
        var scheduler = new EdfScheduler(2);
        var future = Instant.now().plusSeconds(60);

        scheduler.submit(request("r1", future, 1));
        scheduler.submit(request("r2", future, 1));

        assertThatThrownBy(() -> scheduler.submit(request("r3", future, 1)))
                .isInstanceOf(InferXException.BackpressureViolation.class);
    }

    @Test
    void pollNextReturnsEmptyWhenEmpty() {
        var scheduler = new EdfScheduler(10);
        assertThat(scheduler.pollNext()).isEmpty();
    }

    @Test
    void queueSizeTracking() {
        var scheduler = new EdfScheduler(100);
        var future = Instant.now().plusSeconds(60);

        assertThat(scheduler.queueSize()).isZero();
        scheduler.submit(request("r1", future, 1));
        scheduler.submit(request("r2", future, 1));
        assertThat(scheduler.queueSize()).isEqualTo(2);

        scheduler.pollNext();
        assertThat(scheduler.queueSize()).isEqualTo(1);
    }

    @Test
    void countersTrackCorrectly() {
        var scheduler = new EdfScheduler(100);
        var future = Instant.now().plusSeconds(60);

        scheduler.submit(request("r1", future, 1));
        scheduler.submit(request("r2", future, 1));
        assertThat(scheduler.totalScheduled()).isEqualTo(2);

        scheduler.pollNext();
        assertThat(scheduler.totalCompleted()).isEqualTo(1);
    }

    @Test
    void noDeadlineRequestsScheduledLast() {
        var scheduler = new EdfScheduler(100);
        var deadline = Instant.now().plusSeconds(10);

        scheduler.submit(requestNoDeadline("r-no-deadline", 1));
        scheduler.submit(request("r-with-deadline", deadline, 1));

        var first = scheduler.pollNext().orElseThrow();
        assertThat(first.request().id()).isEqualTo("r-with-deadline");
    }

    @Test
    void rejectsInvalidQueueSize() {
        assertThatThrownBy(() -> new EdfScheduler(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- helpers ---

    private static InferRequest request(String id, Instant deadline, int priority) {
        return new InferRequest(id, "model", new InferRequest.Input.Text("test"),
                Map.of(), deadline, priority, Map.of(), Instant.now());
    }

    private static InferRequest requestNoDeadline(String id, int priority) {
        return new InferRequest(id, "model", new InferRequest.Input.Text("test"),
                Map.of(), null, priority, Map.of(), Instant.now());
    }
}
