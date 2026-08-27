package com.pico.infrastructure.agent;

import com.pico.shared.domain.DomainException;

import java.util.Map;

final class ToolArguments {
    private ToolArguments() {
    }

    static String requiredString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new DomainException("INVALID_TOOL_ARGUMENTS", "Tool argument '" + name + "' must be a non-blank string");
        }
        return string;
    }
}
