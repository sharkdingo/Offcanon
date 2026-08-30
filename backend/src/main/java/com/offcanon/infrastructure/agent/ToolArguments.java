package com.offcanon.infrastructure.agent;

import com.offcanon.shared.domain.DomainException;

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

    static String requiredStringValue(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String string)) {
            throw new DomainException("INVALID_TOOL_ARGUMENTS", "Tool argument '" + name + "' must be a string");
        }
        return string;
    }
}
