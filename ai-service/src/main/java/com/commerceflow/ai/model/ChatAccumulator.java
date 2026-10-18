package com.commerceflow.ai.model;

import java.util.*;
import java.util.function.Consumer;
import org.springframework.ai.ollama.api.OllamaApi.*;

/**
 * Bounded transport aggregation; never exposes partial output or hidden thinking as a final answer.
 */
final class ChatAccumulator {
  private final StringBuilder content = new StringBuilder();
  private final Map<String, Message.ToolCall> calls = new LinkedHashMap<>();
  private final Consumer<String> firstOutput;
  private ChatResponse last;
  private int parts;
  private boolean textSeen, toolSeen;

  ChatAccumulator(Consumer<String> firstOutput) {
    this.firstOutput = firstOutput;
  }

  synchronized void accept(ChatResponse part) {
    if (++parts > 2048) throw new IllegalStateException("Model stream exceeds its chunk limit");
    last = part;
    var message = part.message();
    if (message == null) return;
    if (message.content() != null && !message.content().isEmpty()) {
      if (!textSeen) {
        textSeen = true;
        firstOutput.accept("text");
      }
      content.append(message.content());
      if (content.length() > 16000)
        throw new IllegalStateException("Model stream exceeds its content limit");
    }
    if (message.toolCalls() != null)
      for (var call : message.toolCalls()) {
        if (!toolSeen) {
          toolSeen = true;
          firstOutput.accept("tool");
        }
        String key =
            call.function().index() != null ? Integer.toString(call.function().index()) : call.id();
        if (key == null) throw new IllegalStateException("Missing tool request identity");
        calls.put(key, call);
        if (calls.size() > 8) throw new IllegalStateException("Model requested too many tools");
      }
  }

  synchronized ChatResponse finish() {
    if (last == null || !Boolean.TRUE.equals(last.done()))
      throw new IllegalStateException("Model stream ended before completion");
    return new ChatResponse(
        last.model(),
        last.createdAt(),
        new Message(
            Message.Role.ASSISTANT,
            content.toString(),
            List.of(),
            List.copyOf(calls.values()),
            null,
            null),
        last.doneReason(),
        last.done(),
        last.totalDuration(),
        last.loadDuration(),
        last.promptEvalCount(),
        last.promptEvalDuration(),
        last.evalCount(),
        last.evalDuration());
  }
}
