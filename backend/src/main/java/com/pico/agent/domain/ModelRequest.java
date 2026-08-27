package com.pico.agent.domain;

import java.util.List;
import java.util.Objects;
import java.time.Duration;

public record ModelRequest(List<ModelMessage> messages, List<ToolDefinition> tools, Duration timeout) {
    public ModelRequest(List<ModelMessage> messages, List<ToolDefinition> tools) {
        this(messages, tools, Duration.ofSeconds(120));
    }

    public ModelRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) timeout = Duration.ofMillis(1);
    }
}
