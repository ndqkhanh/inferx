package com.inferx.scheduler;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.*;

class WorkStealingPoolTest {

    @Test
    void submitAndTakeFromOwnDeque() {
        var pool = new WorkStealingPool<String>(4);
        pool.submit(0, "task-a");
        pool.submit(0, "task-b");

        // LIFO: last submitted is taken first
        assertThat(pool.takeOrSteal(0)).isEqualTo("task-b");
        assertThat(pool.takeOrSteal(0)).isEqualTo("task-a");
    }

    @Test
    void stealFromOtherWorker() {
        var pool = new WorkStealingPool<String>(2);
        pool.submit(0, "task-a");
        pool.submit(0, "task-b");

        // Worker 1 has no tasks, steals from worker 0 (FIFO — takes first submitted)
        assertThat(pool.takeOrSteal(1)).isEqualTo("task-a");
    }

    @Test
    void returnsNullWhenAllEmpty() {
        var pool = new WorkStealingPool<String>(4);
        assertThat(pool.takeOrSteal(0)).isNull();
    }

    @Test
    void roundRobinSubmission() {
        var pool = new WorkStealingPool<String>(3);
        for (int i = 0; i < 9; i++) {
            pool.submit("task-" + i);
        }
        // Each worker should have 3 tasks
        assertThat(pool.dequeSize(0)).isEqualTo(3);
        assertThat(pool.dequeSize(1)).isEqualTo(3);
        assertThat(pool.dequeSize(2)).isEqualTo(3);
    }

    @Test
    void totalPendingAccurate() {
        var pool = new WorkStealingPool<String>(2);
        pool.submit(0, "a");
        pool.submit(1, "b");
        pool.submit(1, "c");
        assertThat(pool.totalPending()).isEqualTo(3);
    }

    @Test
    void loadImbalanceCalculation() {
        var pool = new WorkStealingPool<String>(2);
        // Perfectly balanced
        pool.submit(0, "a");
        pool.submit(1, "b");
        assertThat(pool.loadImbalance()).isEqualTo(1.0);

        // Imbalanced: worker 0 has 2 more
        pool.submit(0, "c");
        pool.submit(0, "d");
        // worker 0: 3, worker 1: 1, total: 4, avg: 2, max: 3
        assertThat(pool.loadImbalance()).isEqualTo(1.5);
    }

    @Test
    void emptyPoolImbalanceIsOne() {
        var pool = new WorkStealingPool<String>(4);
        assertThat(pool.loadImbalance()).isEqualTo(1.0);
    }

    @Test
    void stealCountTracking() {
        var pool = new WorkStealingPool<String>(2);
        pool.submit(0, "task");

        assertThat(pool.totalStolen()).isZero();
        pool.takeOrSteal(1); // steal
        assertThat(pool.totalStolen()).isEqualTo(1);
    }

    @Test
    void executedCountTracking() {
        var pool = new WorkStealingPool<String>(2);
        pool.submit(0, "a");
        pool.submit(1, "b");
        pool.takeOrSteal(0);
        pool.takeOrSteal(1);
        assertThat(pool.totalExecuted()).isEqualTo(2);
    }

    @Test
    void rejectsInvalidWorkerId() {
        var pool = new WorkStealingPool<String>(4);
        assertThatThrownBy(() -> pool.submit(-1, "task"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pool.submit(4, "task"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidWorkerCount() {
        assertThatThrownBy(() -> new WorkStealingPool<String>(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentSubmitAndSteal() throws InterruptedException {
        var pool = new WorkStealingPool<Integer>(4);
        int tasksPerWorker = 1000;
        Set<Integer> consumed = ConcurrentHashMap.newKeySet();
        var latch = new CountDownLatch(4);

        // 4 workers each submit 1000 tasks then steal until empty
        for (int w = 0; w < 4; w++) {
            final int workerId = w;
            Thread.startVirtualThread(() -> {
                try {
                    for (int i = 0; i < tasksPerWorker; i++) {
                        pool.submit(workerId, workerId * tasksPerWorker + i);
                    }
                    Integer task;
                    while ((task = pool.takeOrSteal(workerId)) != null) {
                        consumed.add(task);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

        // Drain any remaining (race between workers finishing)
        for (int w = 0; w < 4; w++) {
            Integer task;
            while ((task = pool.takeOrSteal(w)) != null) {
                consumed.add(task);
            }
        }

        assertThat(consumed).hasSize(4 * tasksPerWorker);
        assertThat(pool.totalSubmitted()).isEqualTo(4 * tasksPerWorker);
    }

    @Test
    void workStealingReducesImbalance() {
        var pool = new WorkStealingPool<String>(4);
        // Create heavy imbalance: all tasks on worker 0
        for (int i = 0; i < 100; i++) {
            pool.submit(0, "task-" + i);
        }
        assertThat(pool.dequeSize(0)).isEqualTo(100);

        // Workers 1-3 steal
        for (int w = 1; w <= 3; w++) {
            for (int i = 0; i < 25; i++) {
                pool.takeOrSteal(w);
            }
        }

        // Worker 0 should have fewer tasks now
        assertThat(pool.dequeSize(0)).isLessThan(100);
        assertThat(pool.totalStolen()).isGreaterThan(0);
    }
}
