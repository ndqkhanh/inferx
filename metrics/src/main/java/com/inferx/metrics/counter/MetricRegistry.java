package com.inferx.metrics.counter;

import com.inferx.metrics.histogram.HdrHistogram;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Central metric registry for counters, gauges, and histograms.
 * Thread-safe, lock-free operations throughout.
 */
public final class MetricRegistry {

    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> gauges = new ConcurrentHashMap<>();
    private final Map<String, HdrHistogram> histograms = new ConcurrentHashMap<>();

    /** Increment a counter by 1. */
    public void increment(String name) {
        counters.computeIfAbsent(name, k -> new LongAdder()).increment();
    }

    /** Increment a counter by delta. */
    public void increment(String name, long delta) {
        counters.computeIfAbsent(name, k -> new LongAdder()).add(delta);
    }

    /** Get current counter value. */
    public long counterValue(String name) {
        var counter = counters.get(name);
        return counter != null ? counter.sum() : 0;
    }

    /** Set a gauge to an absolute value. */
    public void gauge(String name, long value) {
        gauges.computeIfAbsent(name, k -> new LongAdder()).reset();
        gauges.get(name).add(value);
    }

    /** Get current gauge value. */
    public long gaugeValue(String name) {
        var gauge = gauges.get(name);
        return gauge != null ? gauge.sum() : 0;
    }

    /** Record a value in a histogram (creates with default config if not exists). */
    public void recordHistogram(String name, long value) {
        histograms.computeIfAbsent(name, k -> new HdrHistogram(3_600_000, 2))
                .recordValue(value);
    }

    /** Get a histogram by name. */
    public HdrHistogram histogram(String name) {
        return histograms.get(name);
    }

    /** Get a snapshot of all counter names and values. */
    public Map<String, Long> counterSnapshot() {
        var snapshot = new ConcurrentHashMap<String, Long>();
        counters.forEach((k, v) -> snapshot.put(k, v.sum()));
        return Map.copyOf(snapshot);
    }

    /** Reset all metrics. */
    public void reset() {
        counters.values().forEach(LongAdder::reset);
        gauges.values().forEach(LongAdder::reset);
        histograms.values().forEach(HdrHistogram::reset);
    }
}
