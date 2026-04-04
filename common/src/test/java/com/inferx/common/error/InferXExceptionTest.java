package com.inferx.common.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class InferXExceptionTest {

    @Test
    void routingErrorContainsModelId() {
        var ex = new InferXException.RoutingError("gpt-4");
        assertThat(ex.modelId()).isEqualTo("gpt-4");
        assertThat(ex.getMessage()).contains("gpt-4");
    }

    @Test
    void batchTimeoutContainsBatchSize() {
        var ex = new InferXException.BatchTimeout(32);
        assertThat(ex.batchSize()).isEqualTo(32);
    }

    @Test
    void deadlineMissedContainsOverdue() {
        var ex = new InferXException.DeadlineMissed("req-1", 150);
        assertThat(ex.requestId()).isEqualTo("req-1");
        assertThat(ex.overdueMs()).isEqualTo(150);
    }

    @Test
    void modelUnavailableContainsEndpointId() {
        var ex = new InferXException.ModelUnavailable("ep-42");
        assertThat(ex.endpointId()).isEqualTo("ep-42");
    }

    @Test
    void checkpointFailedContainsIdAndCause() {
        var cause = new RuntimeException("disk full");
        var ex = new InferXException.CheckpointFailed(7, cause);
        assertThat(ex.checkpointId()).isEqualTo(7);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void backpressureViolationContainsOperatorId() {
        var ex = new InferXException.BackpressureViolation("window-agg-1");
        assertThat(ex.operatorId()).isEqualTo("window-agg-1");
    }

    @Test
    void invalidRequestContainsReason() {
        var ex = new InferXException.InvalidRequest("empty input");
        assertThat(ex.getMessage()).contains("empty input");
    }

    @Test
    void sealedHierarchyPatternMatchingDispatch() {
        InferXException ex = new InferXException.RoutingError("m1");
        String result = dispatch(ex);
        assertThat(result).isEqualTo("routing:m1");

        assertThat(dispatch(new InferXException.BatchTimeout(32))).isEqualTo("batch:32");
        assertThat(dispatch(new InferXException.DeadlineMissed("r1", 100))).isEqualTo("deadline:r1");
        assertThat(dispatch(new InferXException.ModelUnavailable("ep-1"))).isEqualTo("unavailable:ep-1");
        assertThat(dispatch(new InferXException.CheckpointFailed(5, null))).isEqualTo("checkpoint:5");
        assertThat(dispatch(new InferXException.BackpressureViolation("op-1"))).isEqualTo("backpressure:op-1");
        assertThat(dispatch(new InferXException.InvalidRequest("bad"))).isEqualTo("invalid");
    }

    private static String dispatch(InferXException ex) {
        if (ex instanceof InferXException.RoutingError e) return "routing:" + e.modelId();
        if (ex instanceof InferXException.BatchTimeout e) return "batch:" + e.batchSize();
        if (ex instanceof InferXException.DeadlineMissed e) return "deadline:" + e.requestId();
        if (ex instanceof InferXException.ModelUnavailable e) return "unavailable:" + e.endpointId();
        if (ex instanceof InferXException.CheckpointFailed e) return "checkpoint:" + e.checkpointId();
        if (ex instanceof InferXException.BackpressureViolation e) return "backpressure:" + e.operatorId();
        if (ex instanceof InferXException.InvalidRequest) return "invalid";
        throw new AssertionError("Unhandled subtype: " + ex.getClass().getSimpleName());
    }
}
