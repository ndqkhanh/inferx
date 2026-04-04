package com.inferx.scheduler;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Work-stealing thread pool (Blumofe & Leiserson 1999, Cilk).
 *
 * Each worker has its own deque. Workers push/pop from the bottom of their
 * own deque (LIFO for locality). When a worker's deque is empty, it steals
 * from the top of another worker's deque (FIFO for fairness).
 *
 * @param <T> the task type
 */
public final class WorkStealingPool<T> {

    private final ConcurrentLinkedDeque<T>[] deques;
    private final int workerCount;
    private final AtomicLong totalSubmitted = new AtomicLong(0);
    private final AtomicLong totalStolen = new AtomicLong(0);
    private final AtomicLong totalExecuted = new AtomicLong(0);

    @SuppressWarnings("unchecked")
    public WorkStealingPool(int workerCount) {
        if (workerCount <= 0) throw new IllegalArgumentException("workerCount must be > 0");
        this.workerCount = workerCount;
        this.deques = new ConcurrentLinkedDeque[workerCount];
        for (int i = 0; i < workerCount; i++) {
            deques[i] = new ConcurrentLinkedDeque<>();
        }
    }

    /**
     * Submit a task to a specific worker's deque (bottom push).
     */
    public void submit(int workerId, T task) {
        validateWorkerId(workerId);
        deques[workerId].addLast(task);
        totalSubmitted.incrementAndGet();
    }

    /**
     * Submit a task using round-robin assignment.
     */
    public void submit(T task) {
        int worker = (int) (totalSubmitted.get() % workerCount);
        submit(worker, task);
    }

    /**
     * Take a task from the worker's own deque (bottom pop — LIFO for locality).
     * If empty, steal from another worker (top pop — FIFO for fairness).
     *
     * @return the task, or null if all deques are empty
     */
    public T takeOrSteal(int workerId) {
        validateWorkerId(workerId);

        // Try own deque first (LIFO — temporal locality)
        T task = deques[workerId].pollLast();
        if (task != null) {
            totalExecuted.incrementAndGet();
            return task;
        }

        // Steal from others (FIFO — fairness, reduces contention)
        for (int i = 1; i < workerCount; i++) {
            int victim = (workerId + i) % workerCount;
            task = deques[victim].pollFirst();
            if (task != null) {
                totalStolen.incrementAndGet();
                totalExecuted.incrementAndGet();
                return task;
            }
        }

        return null; // All deques empty
    }

    /**
     * Get the size of a specific worker's deque.
     */
    public int dequeSize(int workerId) {
        validateWorkerId(workerId);
        return deques[workerId].size();
    }

    /**
     * Get the total number of pending tasks across all workers.
     */
    public int totalPending() {
        int total = 0;
        for (var deque : deques) {
            total += deque.size();
        }
        return total;
    }

    /**
     * Compute load imbalance: max_deque_size / avg_deque_size.
     * 1.0 = perfectly balanced, higher = more imbalanced.
     */
    public double loadImbalance() {
        if (totalPending() == 0) return 1.0;
        double avg = (double) totalPending() / workerCount;
        int max = 0;
        for (var deque : deques) {
            max = Math.max(max, deque.size());
        }
        return avg > 0 ? max / avg : 1.0;
    }

    public int workerCount() { return workerCount; }
    public long totalSubmitted() { return totalSubmitted.get(); }
    public long totalStolen() { return totalStolen.get(); }
    public long totalExecuted() { return totalExecuted.get(); }

    private void validateWorkerId(int workerId) {
        if (workerId < 0 || workerId >= workerCount) {
            throw new IllegalArgumentException("Invalid workerId: " + workerId +
                    " (must be 0-" + (workerCount - 1) + ")");
        }
    }
}
