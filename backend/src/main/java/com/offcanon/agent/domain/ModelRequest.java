package com.offcanon.agent.domain;

import java.util.List;
import java.util.Objects;
import java.time.Duration;

public record ModelRequest(List<ModelMessage> messages,
                           List<ToolDefinition> tools,
                           Duration timeout,
                           String modelEndpoint,
                           String modelName,
                           String modelApiKey) {
    public ModelRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        Objects.requireNonNull(timeout, "timeout");
        modelEndpoint = modelEndpoint == null ? "" : modelEndpoint.trim();
        modelName = modelName == null ? "" : modelName.trim();
        modelApiKey = modelApiKey == null ? "" : modelApiKey.trim();
        if (timeout.isNegative() || timeout.isZero()) timeout = Duration.ofMillis(1);
    }

}
