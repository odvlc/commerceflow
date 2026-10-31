/*
 * Exercises chunk aggregation and transport bounds so incomplete or oversized model output fails
 * explicitly.
 */
package com.commerceflow.ai.model;

import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaApi.ChatResponse;
import org.springframework.ai.ollama.api.OllamaApi.Message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatAccumulatorTest {

    ChatResponse part(String text, boolean done, List<Message.ToolCall> calls) {
        return new ChatResponse(
                "llama3.1:8b",
                Instant.now(),
                new Message(
                        Message.Role.ASSISTANT,
                        text,
                        List.of(),
                        calls,
                        null,
                        "private thinking must be omitted"),
                done ? "stop" : null,
                done,
                1L,
                1L,
                10,
                1L,
                5,
                1L);
    }

    @Test
    void onlyCompletedObservableOutputCanBecomeAnAnswer() {
        // Try completion before the done marker to prove partial text cannot be mistaken for an
        // accepted final result.
        var first = new ArrayList<String>();
        var accumulator = new ChatAccumulator(first::add);
        accumulator.accept(part("Hello ", false, List.of()));
        assertThatThrownBy(accumulator::finish).hasMessageContaining("before completion");
        accumulator.accept(part("world", true, List.of()));
        var result = accumulator.finish();
        assertThat(result.message().content()).isEqualTo("Hello world");
        assertThat(result.message().thinking()).isNull();
        assertThat(result.evalCount()).isEqualTo(5);
        assertThat(first).containsExactly("text");
    }

    @Test
    void toolRequestsStayStructuredAndHaveSeparateFirstOutputTiming() {
        // Structured tool output is observable work but is not text-token latency; record the two
        // paths separately.
        var first = new ArrayList<String>();
        var accumulator = new ChatAccumulator(first::add);
        var call = new Message.ToolCall("request", new Message.ToolCallFunction("getMyCart", Map.of(), 0));
        accumulator.accept(part("", false, List.of(call)));
        accumulator.accept(part("", true, List.of()));
        assertThat(accumulator.finish().message().toolCalls()).containsExactly(call);
        assertThat(first).containsExactly("tool");
    }

    @Test
    void oversizedStreamsFailWithoutAFinalAnswer() {
        var accumulator = new ChatAccumulator(kind -> {
        });
        assertThatThrownBy(() -> accumulator.accept(part("x".repeat(16001), false, List.of())))
                .hasMessageContaining("content limit");
        assertThatThrownBy(accumulator::finish).hasMessageContaining("before completion");
    }
}
