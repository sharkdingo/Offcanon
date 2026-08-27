package com.pico.agent.domain;

import java.util.List;

public record ModelResponse(String text, List<ToolCall> toolCalls, String finishReason) {
    public ModelResponse {
        text = text == null ? "" : text;
        toolCalls = List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
