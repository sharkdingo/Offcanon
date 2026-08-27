package com.offcanon.agent.domain;

import java.util.Objects;

public record ModelMessage(Role role, String content, String toolCallId, String toolName, java.util.List<ToolCall> toolCalls) {
    public ModelMessage(Role role, String content, String toolCallId, String toolName) {
        this(role, content, toolCallId, toolName, java.util.List.of());
    }

    public ModelMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        toolCalls = toolCalls == null ? java.util.List.of() : java.util.List.copyOf(toolCalls);
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
