package com.inferx.common.error;

/**
 * Sealed exception hierarchy for InferX.
 * Pattern-matching in catch blocks ensures exhaustive error handling.
 */
public sealed class InferXException extends RuntimeException
        permits InferXException.RoutingError,
                InferXException.BatchTimeout,
                InferXException.DeadlineMissed,
                InferXException.ModelUnavailable,
                InferXException.CheckpointFailed,
                InferXException.BackpressureViolation,
                InferXException.InvalidRequest {

    public InferXException(String message) { super(message); }
    public InferXException(String message, Throwable cause) { super(message, cause); }

    /** No healthy endpoint found for the requested model. */
    public static final class RoutingError extends InferXException {
        private final String modelId;
        public RoutingError(String modelId) {
            super("No healthy endpoint for model: " + modelId);
            this.modelId = modelId;
        }
        public String modelId() { return modelId; }
    }

    /** Batch was not flushed within the configured timeout. */
    public static final class BatchTimeout extends InferXException {
        private final int batchSize;
        public BatchTimeout(int batchSize) {
            super("Batch timeout with " + batchSize + " pending requests");
            this.batchSize = batchSize;
        }
        public int batchSize() { return batchSize; }
    }

    /** Request could not be completed before its SLO deadline. */
    public static final class DeadlineMissed extends InferXException {
        private final String requestId;
        private final long overdueMs;
        public DeadlineMissed(String requestId, long overdueMs) {
            super("Deadline missed for request " + requestId + " by " + overdueMs + "ms");
            this.requestId = requestId;
            this.overdueMs = overdueMs;
        }
        public String requestId() { return requestId; }
        public long overdueMs() { return overdueMs; }
    }

    /** Model endpoint is not available (unhealthy, draining, or not found). */
    public static final class ModelUnavailable extends InferXException {
        private final String endpointId;
        public ModelUnavailable(String endpointId) {
            super("Model endpoint unavailable: " + endpointId);
            this.endpointId = endpointId;
        }
        public String endpointId() { return endpointId; }
    }

    /** Chandy-Lamport checkpoint could not be completed. */
    public static final class CheckpointFailed extends InferXException {
        private final long checkpointId;
        public CheckpointFailed(long checkpointId, Throwable cause) {
            super("Checkpoint " + checkpointId + " failed", cause);
            this.checkpointId = checkpointId;
        }
        public long checkpointId() { return checkpointId; }
    }

    /** Upstream operator is producing faster than downstream can consume. */
    public static final class BackpressureViolation extends InferXException {
        private final String operatorId;
        public BackpressureViolation(String operatorId) {
            super("Backpressure violation at operator: " + operatorId);
            this.operatorId = operatorId;
        }
        public String operatorId() { return operatorId; }
    }

    /** Malformed or invalid inference request. */
    public static final class InvalidRequest extends InferXException {
        public InvalidRequest(String reason) { super("Invalid request: " + reason); }
    }
}
