package com.inferx.metrics.histogram;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * High Dynamic Range (HDR) Histogram for latency percentile tracking.
 * Inspired by Gil Tene's HdrHistogram.
 *
 * Uses a logarithmic bucket structure to record values from 1 to maxValue
 * with a configurable number of significant digits of precision.
 * All operations are lock-free via AtomicLongArray.
 */
public final class HdrHistogram {

    private final long maxValue;
    private final int significantDigits;
    private final int subBucketCount;
    private final int subBucketHalfCount;
    private final int subBucketMask;
    private final int bucketCount;
    private final AtomicLongArray counts;
    private final int totalBuckets;

    /**
     * @param maxValue          the highest value to be tracked
     * @param significantDigits number of significant value digits (1-3)
     */
    public HdrHistogram(long maxValue, int significantDigits) {
        if (maxValue < 1) throw new IllegalArgumentException("maxValue must be >= 1");
        if (significantDigits < 1 || significantDigits > 3) {
            throw new IllegalArgumentException("significantDigits must be 1-3");
        }

        this.maxValue = maxValue;
        this.significantDigits = significantDigits;

        // Sub-bucket count determines precision within each magnitude bucket
        long largestValueWithSingleUnitResolution = 2 * (long) Math.pow(10, significantDigits);
        int subBucketCountMagnitude = (int) Math.ceil(Math.log(largestValueWithSingleUnitResolution) / Math.log(2));
        this.subBucketCount = 1 << subBucketCountMagnitude;
        this.subBucketHalfCount = subBucketCount / 2;
        this.subBucketMask = subBucketCount - 1;

        // Number of magnitude buckets needed
        this.bucketCount = bucketsNeeded(maxValue);
        this.totalBuckets = (bucketCount + 1) * subBucketHalfCount;
        this.counts = new AtomicLongArray(totalBuckets);
    }

    private int bucketsNeeded(long maxValue) {
        long smallestUntrackable = (long) subBucketCount << 1;
        int bucketsNeeded = 1;
        while (smallestUntrackable <= maxValue) {
            smallestUntrackable <<= 1;
            bucketsNeeded++;
        }
        return bucketsNeeded;
    }

    /** Record a single value occurrence. Thread-safe. */
    public void recordValue(long value) {
        if (value < 0) throw new IllegalArgumentException("Negative value: " + value);
        int index = indexForValue(value);
        if (index >= totalBuckets) {
            // Clamp to max bucket
            index = totalBuckets - 1;
        }
        counts.incrementAndGet(index);
    }

    /** Record a value with a count (for batch recording). Thread-safe. */
    public void recordValueWithCount(long value, long count) {
        int index = indexForValue(value);
        if (index >= totalBuckets) index = totalBuckets - 1;
        counts.addAndGet(index, count);
    }

    /** Get the value at a given percentile (0.0 to 100.0). */
    public long getValueAtPercentile(double percentile) {
        double requestedPercentile = Math.min(percentile, 100.0);
        long totalCount = totalCount();
        if (totalCount == 0) return 0;

        long countAtPercentile = Math.max(1,
                (long) (((requestedPercentile / 100.0) * totalCount) + 0.5));

        long runningCount = 0;
        for (int i = 0; i < totalBuckets; i++) {
            runningCount += counts.get(i);
            if (runningCount >= countAtPercentile) {
                return valueFromIndex(i);
            }
        }
        return maxValue;
    }

    /** Total number of recorded values. */
    public long totalCount() {
        long total = 0;
        for (int i = 0; i < totalBuckets; i++) {
            total += counts.get(i);
        }
        return total;
    }

    /** Mean of all recorded values. */
    public double mean() {
        long total = totalCount();
        if (total == 0) return 0.0;
        double sum = 0;
        for (int i = 0; i < totalBuckets; i++) {
            long count = counts.get(i);
            if (count > 0) {
                sum += valueFromIndex(i) * count;
            }
        }
        return sum / total;
    }

    /** Maximum recorded value. */
    public long max() {
        for (int i = totalBuckets - 1; i >= 0; i--) {
            if (counts.get(i) > 0) {
                return valueFromIndex(i);
            }
        }
        return 0;
    }

    /** Minimum recorded value. */
    public long min() {
        for (int i = 0; i < totalBuckets; i++) {
            if (counts.get(i) > 0) {
                return valueFromIndex(i);
            }
        }
        return 0;
    }

    /** Reset all counts to zero. */
    public void reset() {
        for (int i = 0; i < totalBuckets; i++) {
            counts.set(i, 0);
        }
    }

    private int indexForValue(long value) {
        if (value < subBucketCount) {
            return (int) value;
        }
        int bucketIndex = 64 - Long.numberOfLeadingZeros(value) -
                (int) (Math.log(subBucketCount) / Math.log(2));
        int subBucketIndex = (int) (value >> bucketIndex) & subBucketMask;
        return subBucketHalfCount + (bucketIndex * subBucketHalfCount) + subBucketIndex;
    }

    private long valueFromIndex(int index) {
        if (index < subBucketCount) {
            return index;
        }
        int adjusted = index - subBucketHalfCount;
        int bucketIndex = adjusted / subBucketHalfCount;
        int subBucketIndex = adjusted % subBucketHalfCount;
        return ((long) (subBucketHalfCount + subBucketIndex)) << bucketIndex;
    }
}
