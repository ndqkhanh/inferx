package com.inferx.scheduler;

import com.inferx.common.error.InferXException;
import com.inferx.common.model.InferRequest;

import java.util.Optional;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Earliest Deadline First (EDF) scheduler (Liu & Layland 1973).
 *
 * Requests are ordered by their SLO deadline — the request closest to its
 * deadline is served first. Requests that have already missed their deadline
 * are rejected immediately.
 *
 * Thread-safe via PriorityBlockingQueue (lock-free reads, locked writes).
 */
public final class EdfScheduler {

    private final PriorityBlockingQueue<ScheduledTask> queue;
    private final int maxQueueSize;
    private final AtomicLong totalScheduled = new AtomicLong(0);
    private final AtomicLong totalDeadlineMisses = new AtomicLong(0);
    private final AtomicLong totalCompleted = new AtomicLong(0);

    public EdfScheduler(int maxQueueSize) {
        if (maxQueueSize <= 0) throw new IllegalArgumentException("maxQueueSize must be > 0");
        this.maxQueueSize = maxQueueSize;
        this.queue = new PriorityBlockingQueue<>(maxQueueSize);
    }

    /**
     * Submit a request to be scheduled. Rejects if deadline already missed
     * or queue is full.
     *
     * @return the scheduled task wrapper
     */
    public ScheduledTask submit(InferRequest request) {
        if (request.remainingMs() <= 0) {
            totalDeadlineMisses.incrementAndGet();
            throw new InferXException.DeadlineMissed(request.id(), -request.remainingMs());
        }

        if (queue.size() >= maxQueueSize) {
            throw new InferXException.BackpressureViolation("edf-scheduler");
        }

        var task = new ScheduledTask(request, totalScheduled.getAndIncrement());
        queue.offer(task);
        return task;
    }

    /**
     * Take the highest-priority (earliest deadline) task.
     * Blocks until a task is available.
     * Skips tasks whose deadlines have passed.
     */
    public ScheduledTask takeNext() throws InterruptedException {
        while (true) {
            ScheduledTask task = queue.take();
            if (task.request().remainingMs() > 0) {
                totalCompleted.incrementAndGet();
                return task;
            }
            // Deadline missed while waiting in queue
            totalDeadlineMisses.incrementAndGet();
        }
    }

    /**
     * Poll for the next task without blocking.
     * Returns empty if no ready task, skips deadline-missed tasks.
     */
    public Optional<ScheduledTask> pollNext() {
        while (true) {
            ScheduledTask task = queue.poll();
            if (task == null) return Optional.empty();
            if (task.request().remainingMs() > 0) {
                totalCompleted.incrementAndGet();
                return Optional.of(task);
            }
            totalDeadlineMisses.incrementAndGet();
        }
    }

    public int queueSize() { return queue.size(); }
    public long totalScheduled() { return totalScheduled.get(); }
    public long totalDeadlineMisses() { return totalDeadlineMisses.get(); }
    public long totalCompleted() { return totalCompleted.get(); }

    /**
     * A request wrapped with scheduling metadata, ordered by deadline (ascending).
     */
    public record ScheduledTask(InferRequest request, long sequenceNumber)
            implements Comparable<ScheduledTask> {

        @Override
        public int compareTo(ScheduledTask other) {
            long thisDeadline = request.deadline() != null
                    ? request.deadline().toEpochMilli() : Long.MAX_VALUE;
            long otherDeadline = other.request().deadline() != null
                    ? other.request().deadline().toEpochMilli() : Long.MAX_VALUE;
            int cmp = Long.compare(thisDeadline, otherDeadline);
            if (cmp != 0) return cmp;
            // Tie-break by priority (higher priority first)
            cmp = Integer.compare(other.request().priority(), request.priority());
            if (cmp != 0) return cmp;
            // Tie-break by sequence number (FIFO)
            return Long.compare(sequenceNumber, other.sequenceNumber());
        }
    }
}
