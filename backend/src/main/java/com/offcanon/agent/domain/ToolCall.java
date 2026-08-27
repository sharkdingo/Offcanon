package com.offcanon.agent.domain;

import java.util.Map;
import java.util.Objects;

public record ToolCall(String id, String name, Map<String, Object> arguments) {
    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
