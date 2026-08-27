package com.pico.agent.domain;

import java.util.List;
import java.util.Objects;

public record ModelRequest(List<ModelMessage> messages, List<ToolDefinition> tools) {
    public ModelRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
    }
}
