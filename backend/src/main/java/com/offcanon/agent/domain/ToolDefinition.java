package com.offcanon.agent.domain;

import java.util.Map;
import java.util.Objects;

public record ToolDefinition(String name, String description, Map<String, Object> parameters) {
    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        parameters = Map.copyOf(parameters);
    }
}
