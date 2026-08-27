package com.offcanon.agent.domain;

import java.util.Objects;

public record ToolResult(String callId, String toolName, boolean success, String output, String error) {
    public ToolResult {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(toolName, "toolName");
        output = output == null ? "" : output;
    }

    public static ToolResult success(String callId, String toolName, String output) {
        return new ToolResult(callId, toolName, true, output, null);
    }

    public static ToolResult failure(String callId, String toolName, String error) {
        return new ToolResult(callId, toolName, false, "", error);
    }

    public String asObservation() {
        if (success) {
            return output;
        }
        return "TOOL_ERROR: " + (error == null ? "unknown error" : error);
    }
}
