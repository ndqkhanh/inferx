package com.inferx.common.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable inference response returned from a model backend.
 *
 * @param requestId      original request identifier
 * @param modelId        model that produced the response
 * @param modelVersion   specific version used
 * @param output         the generated output
 * @param usage          token/compute usage statistics
 * @param latency        end-to-end latency
 * @param completedAt    wall-clock completion time
 */
public record InferResponse(
        String requestId,
        String modelId,
        String modelVersion,
        Output output,
        Usage usage,
        Duration latency,
        Instant completedAt
) {
    public InferResponse {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(output, "output");
        if (completedAt == null) completedAt = Instant.now();
    }

    /** The polymorphic output payload. */
    public sealed interface Output permits Output.Text, Output.TokenIds, Output.Embedding, Output.Choices {
        record Text(String content) implements Output {}
        record TokenIds(List<Integer> tokens) implements Output {}
        record Embedding(float[] vector) implements Output {}
        record Choices(List<Choice> choices) implements Output {}
    }

    public record Choice(int index, String text, String finishReason) {}

    /** Resource usage for cost tracking. */
    public record Usage(int inputTokens, int outputTokens, Duration computeTime) {
        public int totalTokens() { return inputTokens + outputTokens; }
    }
}
