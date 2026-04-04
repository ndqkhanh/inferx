package com.inferx.batcher;

import com.inferx.common.model.InferRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Deadline-aware adaptive request batcher.
 *
 * Collects incoming inference requests into batches for efficient processing.
 * Uses a PID controller to dynamically adjust the target batch size based on
 * observed fill rate vs. target fill rate.
 *
 * Two flush triggers:
 * 1. Batch reaches target size (PID-controlled)
 * 2. Earliest deadline in the batch is approaching (deadline flush)
 *
 * Thread-safe via ReentrantLock (low contention — one lock per flush).
 */
public final class DeadlineAwareBatcher {

    private final int maxBatchSize;
    private final long deadlineBufferMs;
    private final PidController pidController;
    private final ReentrantLock lock = new ReentrantLock();

    private List<InferRequest> currentBatch;
    private int targetBatchSize;
    private long totalBatches;
    private long totalRequests;
    private long totalDeadlineFlushes;
    private long totalSizeFlushes;

    /**
     * @param maxBatchSize      absolute maximum batch size
     * @param initialBatchSize  starting target batch size
     * @param deadlineBufferMs  flush when earliest deadline is within this many ms
     * @param targetFillRate    target fill rate (0.0 to 1.0) for PID controller
     */
    public DeadlineAwareBatcher(int maxBatchSize, int initialBatchSize,
                                 long deadlineBufferMs, double targetFillRate) {
        if (maxBatchSize <= 0) throw new IllegalArgumentException("maxBatchSize must be > 0");
        if (initialBatchSize <= 0 || initialBatchSize > maxBatchSize) {
            throw new IllegalArgumentException("initialBatchSize must be in (0, maxBatchSize]");
        }
        this.maxBatchSize = maxBatchSize;
        this.targetBatchSize = initialBatchSize;
        this.deadlineBufferMs = deadlineBufferMs;
        this.currentBatch = new ArrayList<>();
        this.pidController = new PidController(
                2.0,   // kp: moderate proportional response
                0.1,   // ki: slow integral correction
                0.5,   // kd: moderate damping
                1.0,   // min batch size
                maxBatchSize // max batch size
        );
    }

    /**
     * Add a request to the current batch.
     *
     * @return a flushed batch if flush was triggered, or empty list if still accumulating
     */
    public List<InferRequest> add(InferRequest request) {
        lock.lock();
        try {
            currentBatch.add(request);
            totalRequests++;

            if (shouldFlush()) {
                return doFlush();
            }
            return List.of();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Force-flush the current batch regardless of size.
     *
     * @return the current batch contents (may be empty)
     */
    public List<InferRequest> flush() {
        lock.lock();
        try {
            if (currentBatch.isEmpty()) return List.of();
            return doFlush();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Check if the current batch should be flushed based on deadline pressure.
     * Call this periodically from a timer thread.
     *
     * @return a flushed batch if deadline pressure triggered, or empty list
     */
    public List<InferRequest> checkDeadlineFlush() {
        lock.lock();
        try {
            if (currentBatch.isEmpty()) return List.of();
            if (earliestDeadlineMs() <= deadlineBufferMs) {
                totalDeadlineFlushes++;
                return doFlush();
            }
            return List.of();
        } finally {
            lock.unlock();
        }
    }

    private boolean shouldFlush() {
        if (currentBatch.size() >= targetBatchSize) {
            totalSizeFlushes++;
            return true;
        }
        if (currentBatch.size() >= maxBatchSize) {
            totalSizeFlushes++;
            return true;
        }
        if (earliestDeadlineMs() <= deadlineBufferMs) {
            totalDeadlineFlushes++;
            return true;
        }
        return false;
    }

    private List<InferRequest> doFlush() {
        List<InferRequest> batch = Collections.unmodifiableList(currentBatch);
        double fillRate = (double) batch.size() / targetBatchSize;

        // PID adjusts target batch size based on fill rate feedback
        double newTarget = pidController.compute(0.85, fillRate);
        targetBatchSize = Math.max(1, Math.min(maxBatchSize, (int) Math.round(newTarget)));

        currentBatch = new ArrayList<>();
        totalBatches++;
        return batch;
    }

    private long earliestDeadlineMs() {
        long earliest = Long.MAX_VALUE;
        for (var req : currentBatch) {
            long remaining = req.remainingMs();
            if (remaining < earliest) {
                earliest = remaining;
            }
        }
        return earliest;
    }

    public int currentBatchSize() {
        lock.lock();
        try { return currentBatch.size(); }
        finally { lock.unlock(); }
    }

    public int targetBatchSize() { return targetBatchSize; }
    public long totalBatches() { return totalBatches; }
    public long totalRequests() { return totalRequests; }
    public long totalDeadlineFlushes() { return totalDeadlineFlushes; }
    public long totalSizeFlushes() { return totalSizeFlushes; }
}
