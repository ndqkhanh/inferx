package com.inferx.common.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Snowflake-inspired ID generator for request/event IDs.
 * Layout: [timestamp_ms 41 bits][node_id 10 bits][sequence 13 bits]
 * Supports ~8192 IDs/ms per node, ~69 years from epoch.
 */
public final class IdGenerator {

    private static final long EPOCH = 1_700_000_000_000L; // Nov 2023
    private static final int NODE_BITS = 10;
    private static final int SEQUENCE_BITS = 13;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
    private static final long MAX_NODE_ID = (1L << NODE_BITS) - 1;

    private final long nodeId;
    private final AtomicLong state = new AtomicLong(0);

    public IdGenerator(long nodeId) {
        if (nodeId < 0 || nodeId > MAX_NODE_ID) {
            throw new IllegalArgumentException("nodeId must be in [0, " + MAX_NODE_ID + "]: " + nodeId);
        }
        this.nodeId = nodeId;
    }

    /**
     * Generate a globally unique, roughly time-ordered ID.
     * Thread-safe via CAS loop.
     */
    public long nextId() {
        while (true) {
            long now = System.currentTimeMillis() - EPOCH;
            long prev = state.get();
            long prevTs = prev >> (NODE_BITS + SEQUENCE_BITS);
            long seq = (prevTs == now) ? ((prev & MAX_SEQUENCE) + 1) : 0;

            if (seq > MAX_SEQUENCE) {
                Thread.onSpinWait();
                continue;
            }

            long next = (now << (NODE_BITS + SEQUENCE_BITS))
                    | (nodeId << SEQUENCE_BITS)
                    | seq;

            if (state.compareAndSet(prev, next)) {
                return next;
            }
        }
    }

    /** Convert a generated ID to a compact string representation. */
    public static String toBase36(long id) {
        return Long.toString(id, 36);
    }
}
