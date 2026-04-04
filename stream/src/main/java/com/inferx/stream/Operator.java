package com.inferx.stream;

import com.inferx.common.model.StreamEvent;

import java.util.List;

/**
 * Sealed operator hierarchy for the stream processing engine.
 * Each operator transforms a stream of events.
 * Pattern matching ensures exhaustive dispatch at compile time.
 */
public sealed interface Operator
        permits Operator.Map, Operator.Filter, Operator.FlatMap,
                Operator.Window, Operator.Aggregate, Operator.Sink {

    String id();

    /** Transform each event into exactly one output event. */
    record Map(String id, java.util.function.Function<StreamEvent, StreamEvent> mapper)
            implements Operator {}

    /** Keep only events matching the predicate. */
    record Filter(String id, java.util.function.Predicate<StreamEvent> predicate)
            implements Operator {}

    /** Transform each event into zero or more output events. */
    record FlatMap(String id, java.util.function.Function<StreamEvent, List<StreamEvent>> mapper)
            implements Operator {}

    /** Buffer events into windows based on a windowing strategy. */
    record Window(String id, WindowStrategy strategy)
            implements Operator {}

    /** Reduce a window of events into a single result. */
    record Aggregate(String id, String windowOperatorId,
                     java.util.function.BiFunction<Object, StreamEvent, Object> accumulator,
                     Object initialState)
            implements Operator {}

    /** Terminal operator — outputs events to an external system. */
    record Sink(String id, java.util.function.Consumer<StreamEvent> output)
            implements Operator {}
}
