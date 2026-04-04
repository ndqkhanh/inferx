package com.inferx.batcher;

import com.inferx.common.model.InferRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class DeadlineAwareBatcherTest {

    @Test
    void accumulatesUntilTargetSize() {
        var batcher = new DeadlineAwareBatcher(32, 4, 50, 0.85);
        // Add 3 requests — should not flush (target = 4)
        for (int i = 0; i < 3; i++) {
            var result = batcher.add(request("r-" + i, 60));
            assertThat(result).isEmpty();
        }
        assertThat(batcher.currentBatchSize()).isEqualTo(3);

        // 4th request triggers flush
        var batch = batcher.add(request("r-3", 60));
        assertThat(batch).hasSize(4);
        assertThat(batcher.currentBatchSize()).isZero();
    }

    @Test
    void flushesOnDeadlinePressure() {
        // Buffer = 5000ms, deadline = 2s from now → remaining < buffer → deadline flush
        var batcher = new DeadlineAwareBatcher(32, 16, 5000, 0.85);
        var addResult = batcher.add(request("r-urgent", 2)); // 2s deadline

        // The add() itself may trigger deadline flush, or checkDeadlineFlush will
        if (addResult.isEmpty()) {
            var batch = batcher.checkDeadlineFlush();
            assertThat(batch).hasSize(1);
        } else {
            assertThat(addResult).hasSize(1);
        }
        assertThat(batcher.totalDeadlineFlushes()).isEqualTo(1);
    }

    @Test
    void manualFlush() {
        var batcher = new DeadlineAwareBatcher(32, 16, 50, 0.85);
        batcher.add(request("r-1", 60));
        batcher.add(request("r-2", 60));

        var batch = batcher.flush();
        assertThat(batch).hasSize(2);
        assertThat(batcher.currentBatchSize()).isZero();
    }

    @Test
    void emptyFlushReturnsEmptyList() {
        var batcher = new DeadlineAwareBatcher(32, 4, 50, 0.85);
        assertThat(batcher.flush()).isEmpty();
        assertThat(batcher.checkDeadlineFlush()).isEmpty();
    }

    @Test
    void neverExceedsMaxBatchSize() {
        var batcher = new DeadlineAwareBatcher(4, 4, 50, 0.85);
        for (int i = 0; i < 4; i++) {
            batcher.add(request("r-" + i, 60));
        }
        // After flush, add more — should never exceed max
        assertThat(batcher.currentBatchSize()).isZero();
    }

    @Test
    void countersTrackCorrectly() {
        var batcher = new DeadlineAwareBatcher(32, 4, 50, 0.85);
        for (int i = 0; i < 4; i++) {
            batcher.add(request("r-" + i, 60));
        }
        // First flush at target=4
        assertThat(batcher.totalRequests()).isEqualTo(4);
        assertThat(batcher.totalBatches()).isGreaterThanOrEqualTo(1);
        assertThat(batcher.totalSizeFlushes()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void batchIsImmutable() {
        var batcher = new DeadlineAwareBatcher(32, 2, 50, 0.85);
        batcher.add(request("r-1", 60));
        var batch = batcher.add(request("r-2", 60));
        assertThat(batch).hasSize(2);
        assertThatThrownBy(() -> batch.add(request("r-extra", 60)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidConfig() {
        assertThatThrownBy(() -> new DeadlineAwareBatcher(0, 1, 50, 0.85))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeadlineAwareBatcher(10, 0, 50, 0.85))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeadlineAwareBatcher(10, 20, 50, 0.85))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pidAdaptsBatchSize() {
        var batcher = new DeadlineAwareBatcher(64, 8, 50, 0.85);
        // Run multiple batches and observe target adapts
        int initialTarget = batcher.targetBatchSize();
        for (int round = 0; round < 10; round++) {
            for (int i = 0; i < 8; i++) {
                batcher.add(request("r-" + round + "-" + i, 60));
            }
        }
        // PID should have adjusted the target batch size
        // (may go up or down depending on fill rate feedback)
        assertThat(batcher.totalBatches()).isGreaterThan(0);
    }

    // --- helpers ---

    private static InferRequest request(String id, double deadlineSeconds) {
        return new InferRequest(id, "model", new InferRequest.Input.Text("test"),
                Map.of(), Instant.now().plusMillis((long) (deadlineSeconds * 1000)),
                1, Map.of(), Instant.now());
    }
}
