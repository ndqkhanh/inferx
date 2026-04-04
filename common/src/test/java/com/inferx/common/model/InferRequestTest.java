package com.inferx.common.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class InferRequestTest {

    @Test
    void textInputCreation() {
        var input = new InferRequest.Input.Text("Hello world");
        assertThat(input.content()).isEqualTo("Hello world");
    }

    @Test
    void tokenInputDefensiveCopy() {
        var tokens = new java.util.ArrayList<>(List.of(1, 2, 3));
        var input = new InferRequest.Input.TokenIds(tokens);
        tokens.add(4);
        assertThat(input.tokens()).hasSize(3);
    }

    @Test
    void embeddingInputCreation() {
        var input = new InferRequest.Input.Embedding(new float[]{0.1f, 0.2f});
        assertThat(input.vector()).hasSize(2);
    }

    @Test
    void requestRequiresId() {
        assertThatThrownBy(() -> new InferRequest(
                null, "model-1", new InferRequest.Input.Text("hi"),
                Map.of(), Instant.now().plusSeconds(10), 1, Map.of(), Instant.now()
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void requestRequiresModelId() {
        assertThatThrownBy(() -> new InferRequest(
                "req-1", null, new InferRequest.Input.Text("hi"),
                Map.of(), Instant.now().plusSeconds(10), 1, Map.of(), Instant.now()
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void requestRequiresInput() {
        assertThatThrownBy(() -> new InferRequest(
                "req-1", "model-1", null,
                Map.of(), Instant.now().plusSeconds(10), 1, Map.of(), Instant.now()
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void parametersDefaultToEmptyMap() {
        var req = new InferRequest(
                "req-1", "model-1", new InferRequest.Input.Text("hi"),
                null, null, 0, null, null
        );
        assertThat(req.parameters()).isEmpty();
        assertThat(req.metadata()).isEmpty();
        assertThat(req.createdAt()).isNotNull();
    }

    @Test
    void parametersAreImmutable() {
        var params = new java.util.HashMap<String, Object>();
        params.put("temperature", 0.7);
        var req = new InferRequest(
                "req-1", "model-1", new InferRequest.Input.Text("hi"),
                params, null, 0, null, null
        );
        assertThatThrownBy(() -> req.parameters().put("new_key", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void remainingMsPositiveBeforeDeadline() {
        var deadline = Instant.now().plusSeconds(60);
        var req = new InferRequest(
                "req-1", "model-1", new InferRequest.Input.Text("hi"),
                Map.of(), deadline, 1, Map.of(), Instant.now()
        );
        assertThat(req.remainingMs()).isPositive();
    }

    @Test
    void remainingMsMaxWhenNoDeadline() {
        var req = new InferRequest(
                "req-1", "model-1", new InferRequest.Input.Text("hi"),
                Map.of(), null, 1, Map.of(), Instant.now()
        );
        assertThat(req.remainingMs()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void sealedInputExhaustiveSwitch() {
        InferRequest.Input input = new InferRequest.Input.Text("test");
        String result = switch (input) {
            case InferRequest.Input.Text t -> "text:" + t.content();
            case InferRequest.Input.TokenIds t -> "tokens:" + t.tokens().size();
            case InferRequest.Input.Embedding e -> "embedding:" + e.vector().length;
        };
        assertThat(result).isEqualTo("text:test");
    }
}
