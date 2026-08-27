package com.offcanon.agent.domain;

import java.util.List;
import java.util.Objects;
import java.time.Duration;

public record ModelRequest(List<ModelMessage> messages,
                           List<ToolDefinition> tools,
                           Duration timeout,
                           String modelEndpoint,
                           String modelName) {
    public ModelRequest(List<ModelMessage> messages, List<ToolDefinition> tools) {
        this(messages, tools, Duration.ofSeconds(120), "", "");
    }

    public ModelRequest(List<ModelMessage> messages, List<ToolDefinition> tools, Duration timeout) {
        this(messages, tools, timeout, "", "");
    }

    public ModelRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        Objects.requireNonNull(timeout, "timeout");
        modelEndpoint = modelEndpoint == null ? "" : modelEndpoint.trim();
        modelName = modelName == null ? "" : modelName.trim();
        if (timeout.isNegative() || timeout.isZero()) timeout = Duration.ofMillis(1);
    }

    public ModelRequest withProvider(String endpoint, String name) {
        return new ModelRequest(messages, tools, timeout, endpoint, name);
    }
}
