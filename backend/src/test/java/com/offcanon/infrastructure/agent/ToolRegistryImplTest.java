package com.offcanon.infrastructure.agent;

import com.offcanon.agent.domain.ToolCall;
import com.offcanon.agent.domain.ToolDefinition;
import com.offcanon.agent.domain.ToolResult;
import com.offcanon.experiment.domain.Experiment;
import com.offcanon.port.Tool;
import com.offcanon.shared.domain.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryImplTest {
    private final Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(),
            "test", Instant.now());

    @Test
    void rejectsNullToolCallWithAStableDomainError() {
        ToolRegistryImpl registry = new ToolRegistryImpl(List.of());

        DomainException error = assertThrows(DomainException.class,
                () -> registry.dispatch(experiment, null));

        assertEquals("TOOL_CALL_INVALID", error.code());
    }

    @Test
    void convertsNullToolResultToAnIdentityPreservingFailure() {
        ToolRegistryImpl registry = new ToolRegistryImpl(List.of(tool("null_result",
                (exp, id, args) -> null)));

        ToolResult result = registry.dispatch(experiment,
                new ToolCall("call-1", "null_result", Map.of()));

        assertFalse(result.success());
        assertEquals("call-1", result.callId());
        assertEquals("null_result", result.toolName());
        assertTrue(result.asObservation().contains("Malformed tool result"));
    }

    @Test
    void convertsMismatchedToolResultIdentityToAnIdentityPreservingFailure() {
        ToolRegistryImpl registry = new ToolRegistryImpl(List.of(tool("wrong_identity",
                (exp, id, args) -> ToolResult.success("other-call", "other-tool", "unsafe"))));

        ToolResult result = registry.dispatch(experiment,
                new ToolCall("call-2", "wrong_identity", Map.of()));

        assertFalse(result.success());
        assertEquals("call-2", result.callId());
        assertEquals("wrong_identity", result.toolName());
        assertTrue(result.asObservation().contains("mismatched call identity"));
        assertFalse(result.asObservation().contains("unsafe"));
    }

    @Test
    void convertsFailureWithoutAnErrorToAClosedFailure() {
        ToolRegistryImpl registry = new ToolRegistryImpl(List.of(tool("empty_failure",
                (exp, id, args) -> new ToolResult(id, "empty_failure", false, "", null))));

        ToolResult result = registry.dispatch(experiment,
                new ToolCall("call-3", "empty_failure", Map.of()));

        assertFalse(result.success());
        assertEquals("call-3", result.callId());
        assertEquals("empty_failure", result.toolName());
        assertTrue(result.asObservation().contains("Malformed tool result"));
    }

    private Tool tool(String name, Executor executor) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "test tool", Map.of("type", "object"));
            }

            @Override
            public ToolResult execute(Experiment experiment, String callId, Map<String, Object> arguments) {
                return executor.execute(experiment, callId, arguments);
            }
        };
    }

    @FunctionalInterface
    private interface Executor {
        ToolResult execute(Experiment experiment, String callId, Map<String, Object> arguments);
    }
}
