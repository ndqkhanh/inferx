package com.inferx.common.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable inference request flowing through the gateway.
 *
 * @param id          unique request identifier
 * @param modelId     target model identifier (may be resolved by router)
 * @param input       raw input payload (text, tokens, or embeddings)
 * @param parameters  model-specific parameters (temperature, max_tokens, etc.)
 * @param deadline    SLO deadline — request MUST complete before this instant
 * @param priority    scheduling priority (higher = more urgent)
 * @param metadata    caller-supplied metadata for routing/tracking
 * @param createdAt   wall-clock time of request creation
 */
public record InferRequest(
        String id,
        String modelId,
        Input input,
        Map<String, Object> parameters,
        Instant deadline,
        int priority,
        Map<String, String> metadata,
        Instant createdAt
) {
    public InferRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(input, "input");
        parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        if (createdAt == null) createdAt = Instant.now();
    }

    /** The polymorphic input payload. */
    public sealed interface Input permits Input.Text, Input.TokenIds, Input.Embedding {
        record Text(String content) implements Input {
            public Text { Objects.requireNonNull(content, "content"); }
        }

        record TokenIds(List<Integer> tokens) implements Input {
            public TokenIds { tokens = List.copyOf(tokens); }
        }

        record Embedding(float[] vector) implements Input {
            public Embedding { Objects.requireNonNull(vector, "vector"); }
        }
    }

    /** Remaining time until deadline, in milliseconds. Negative means overdue. */
    public long remainingMs() {
        return deadline != null
                ? deadline.toEpochMilli() - System.currentTimeMillis()
                : Long.MAX_VALUE;
    }
}
