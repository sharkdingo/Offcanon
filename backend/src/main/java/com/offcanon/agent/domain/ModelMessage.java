package com.offcanon.agent.domain;

import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

public record ModelMessage(Role role, String content, String toolCallId, String toolName, java.util.List<ToolCall> toolCalls) {
    public ModelMessage(Role role, String content, String toolCallId, String toolName) {
        this(role, content, toolCallId, toolName, java.util.List.of());
    }

    public ModelMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        toolCalls = toolCalls == null ? java.util.List.of() : java.util.List.copyOf(toolCalls);
        if (role == Role.TOOL) {
            if (toolCallId == null || toolCallId.isBlank() || toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("Tool messages require a non-blank call id and tool name");
            }
            if (!toolCalls.isEmpty()) {
                throw new IllegalArgumentException("Tool messages cannot contain assistant tool calls");
            }
        } else {
            if (toolCallId != null || toolName != null) {
                throw new IllegalArgumentException("Only tool messages may carry tool call identity");
            }
            Set<String> callIds = new HashSet<>();
            for (ToolCall call : toolCalls) {
                if (!callIds.add(call.id())) {
                    throw new IllegalArgumentException("Assistant tool call ids must be unique");
                }
            }
        }
    }

    public static ModelMessage system(String content) {
        return new ModelMessage(Role.SYSTEM, content, null, null);
    }

    public static ModelMessage user(String content) {
        return new ModelMessage(Role.USER, content, null, null);
    }

    public static ModelMessage assistant(String content) {
        return new ModelMessage(Role.ASSISTANT, content, null, null);
    }

    public static ModelMessage assistant(String content, java.util.List<ToolCall> toolCalls) {
        return new ModelMessage(Role.ASSISTANT, content, null, null, toolCalls);
    }

    public static ModelMessage tool(String toolCallId, String toolName, String content) {
        return new ModelMessage(Role.TOOL, content, toolCallId, toolName);
    }

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }
}
