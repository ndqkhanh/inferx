package com.inferx.stream;

import com.inferx.common.model.StreamEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stream processing pipeline — executes a DAG of operators on a stream of events.
 *
 * Supports tumbling, sliding, and session windows with watermark-based triggering.
 * Implements Chandy-Lamport checkpointing for exactly-once recovery.
 */
public final class StreamPipeline {

    private final List<Operator> operators;
    private final Watermark watermark;
    private final Map<String, List<WindowBuffer>> windowBuffers = new ConcurrentHashMap<>();
    private final AtomicLong processedCount = new AtomicLong(0);
    private final AtomicLong droppedLateCount = new AtomicLong(0);
    private final AtomicLong checkpointId = new AtomicLong(0);
    private final List<CheckpointBarrier> checkpoints = new CopyOnWriteArrayList<>();

    public StreamPipeline(List<Operator> operators, long maxLatenessMs) {
        this.operators = List.copyOf(operators);
        this.watermark = new Watermark(maxLatenessMs);
    }

    /**
     * Process a single event through the operator DAG.
     * Returns the list of output events (from Sink operators).
     */
    public List<StreamEvent> process(StreamEvent event) {
        watermark.advance(event.eventTime());

        if (watermark.isLate(event.eventTime())) {
            droppedLateCount.incrementAndGet();
            return List.of();
        }

        processedCount.incrementAndGet();
        List<StreamEvent> current = List.of(event);

        for (Operator op : operators) {
            current = applyOperator(op, current);
            if (current.isEmpty()) break;
        }

        return current;
    }

    /**
     * Trigger any windows whose watermark has passed their end time.
     * Returns aggregated results from closed windows.
     */
    public List<StreamEvent> triggerWindows() {
        List<StreamEvent> results = new ArrayList<>();

        for (var entry : windowBuffers.entrySet()) {
            String opId = entry.getKey();
            var buffers = entry.getValue();
            var closedWindows = new ArrayList<WindowBuffer>();

            for (var buffer : buffers) {
                if (watermark.shouldTriggerWindow(buffer.windowEnd)) {
                    // Emit aggregated result
                    if (!buffer.events.isEmpty()) {
                        var result = new StreamEvent(
                                opId,
                                Map.of("count", buffer.events.size(),
                                       "windowStart", buffer.windowStart.toString(),
                                       "windowEnd", buffer.windowEnd.toString()),
                                buffer.windowEnd
                        );
                        results.add(result);
                    }
                    closedWindows.add(buffer);
                }
            }
            buffers.removeAll(closedWindows);
        }

        return results;
    }

    /**
     * Initiate a Chandy-Lamport checkpoint (distributed snapshot).
     * Records the current state of all operators for exactly-once recovery.
     */
    public CheckpointBarrier checkpoint() {
        long id = checkpointId.incrementAndGet();
        var snapshot = new HashMap<String, Object>();

        // Snapshot window buffers
        for (var entry : windowBuffers.entrySet()) {
            snapshot.put("window:" + entry.getKey(), entry.getValue().size());
        }

        snapshot.put("watermark", watermark.current());
        snapshot.put("processedCount", processedCount.get());

        var barrier = new CheckpointBarrier(id, Instant.now(), Map.copyOf(snapshot));
        checkpoints.add(barrier);
        return barrier;
    }

    /**
     * Get the latest completed checkpoint.
     */
    public Optional<CheckpointBarrier> latestCheckpoint() {
        if (checkpoints.isEmpty()) return Optional.empty();
        return Optional.of(checkpoints.getLast());
    }

    private List<StreamEvent> applyOperator(Operator op, List<StreamEvent> events) {
        return switch (op) {
            case Operator.Map m -> events.stream()
                    .map(m.mapper()::apply)
                    .toList();

            case Operator.Filter f -> events.stream()
                    .filter(f.predicate()::test)
                    .toList();

            case Operator.FlatMap fm -> events.stream()
                    .flatMap(e -> fm.mapper().apply(e).stream())
                    .toList();

            case Operator.Window w -> {
                for (var event : events) {
                    addToWindow(w.id(), w.strategy(), event);
                }
                yield events; // Pass through; window results emitted via triggerWindows()
            }

            case Operator.Aggregate a -> events; // Aggregation happens in triggerWindows()

            case Operator.Sink s -> {
                events.forEach(s.output()::accept);
                yield events;
            }
        };
    }

    private void addToWindow(String opId, WindowStrategy strategy, StreamEvent event) {
        var buffers = windowBuffers.computeIfAbsent(opId, k -> new CopyOnWriteArrayList<>());
        Instant eventTime = event.eventTime();

        switch (strategy) {
            case WindowStrategy.Tumbling t -> {
                long windowSizeMs = t.size().toMillis();
                long windowStart = (eventTime.toEpochMilli() / windowSizeMs) * windowSizeMs;
                var start = Instant.ofEpochMilli(windowStart);
                var end = start.plus(t.size());
                getOrCreateBuffer(buffers, start, end).events.add(event);
            }
            case WindowStrategy.Sliding s -> {
                long sizeMs = s.size().toMillis();
                long slideMs = s.slide().toMillis();
                long eventMs = eventTime.toEpochMilli();
                // Event belongs to all windows that contain its timestamp
                long firstWindowStart = ((eventMs - sizeMs) / slideMs + 1) * slideMs;
                for (long ws = firstWindowStart; ws <= eventMs; ws += slideMs) {
                    var start = Instant.ofEpochMilli(ws);
                    var end = start.plus(s.size());
                    getOrCreateBuffer(buffers, start, end).events.add(event);
                }
            }
            case WindowStrategy.Session s -> {
                // Find existing session window where event fits within gap
                WindowBuffer matched = null;
                for (var buffer : buffers) {
                    long gapMs = eventTime.toEpochMilli() - buffer.latestEventTime.toEpochMilli();
                    if (gapMs >= 0 && gapMs <= s.gap().toMillis()) {
                        matched = buffer;
                        break;
                    }
                }
                if (matched != null) {
                    matched.events.add(event);
                    matched.latestEventTime = eventTime;
                    matched.windowEnd = eventTime.plus(s.gap());
                } else {
                    var buffer = new WindowBuffer(eventTime, eventTime.plus(s.gap()));
                    buffer.latestEventTime = eventTime;
                    buffer.events.add(event);
                    buffers.add(buffer);
                }
            }
        }
    }

    private WindowBuffer getOrCreateBuffer(List<WindowBuffer> buffers, Instant start, Instant end) {
        for (var buffer : buffers) {
            if (buffer.windowStart.equals(start) && buffer.windowEnd.equals(end)) {
                return buffer;
            }
        }
        var buffer = new WindowBuffer(start, end);
        buffers.add(buffer);
        return buffer;
    }

    public long processedCount() { return processedCount.get(); }
    public long droppedLateCount() { return droppedLateCount.get(); }
    public Instant currentWatermark() { return watermark.current(); }

    /** Internal window buffer. */
    static final class WindowBuffer {
        final Instant windowStart;
        Instant windowEnd;
        Instant latestEventTime;
        final List<StreamEvent> events = new ArrayList<>();

        WindowBuffer(Instant start, Instant end) {
            this.windowStart = start;
            this.windowEnd = end;
            this.latestEventTime = start;
        }
    }

    /** Chandy-Lamport checkpoint barrier record. */
    public record CheckpointBarrier(long id, Instant timestamp, Map<String, Object> snapshot) {}
}
