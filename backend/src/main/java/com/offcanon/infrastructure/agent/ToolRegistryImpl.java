package com.offcanon.infrastructure.agent;

import com.offcanon.agent.domain.ToolCall;
import com.offcanon.agent.domain.ToolDefinition;
import com.offcanon.agent.domain.ToolResult;
import com.offcanon.experiment.domain.Experiment;
import com.offcanon.port.Tool;
import com.offcanon.port.ToolRegistry;
import com.offcanon.shared.domain.DomainException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Comparator;

@Component
public class ToolRegistryImpl implements ToolRegistry {
    private final Map<String, Tool> tools;
    private final List<ToolDefinition> definitions;

    public ToolRegistryImpl(List<Tool> discoveredTools) {
        Map<String, Tool> byName = new LinkedHashMap<>();
        Map<String, ToolDefinition> definitionByName = new LinkedHashMap<>();
        for (Tool tool : discoveredTools) {
            if (tool == null) {
                throw new IllegalStateException("Tool and tool definition must not be null");
            }
            ToolDefinition definition = tool.definition();
            if (definition == null) {
                throw new IllegalStateException("Tool and tool definition must not be null");
            }
            String name = definition.name();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("Tool name must not be blank");
            }
            if (byName.putIfAbsent(name, tool) != null) {
                throw new IllegalStateException("Duplicate tool: " + name);
            }
            definitionByName.put(name, definition);
        }
        this.tools = Collections.unmodifiableMap(new LinkedHashMap<>(byName));
        this.definitions = definitionByName.values().stream()
                .sorted(Comparator.comparing(ToolDefinition::name))
                .toList();
    }

    @Override
    public List<ToolDefinition> definitions() {
        return definitions;
    }

    @Override
    public ToolResult dispatch(Experiment experiment, ToolCall call) {
        if (call == null) {
            throw new DomainException("TOOL_CALL_INVALID", "Tool call must not be null");
        }
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return ToolResult.failure(call.id(), call.name(), "Unknown tool: " + call.name());
        }
        try {
            ToolResult result = tool.execute(experiment, call.id(), call.arguments());
            if (result == null) {
                return malformedResult(call, "Tool returned no result");
            }
            if (!call.id().equals(result.callId()) || !call.name().equals(result.toolName())) {
                return malformedResult(call, "Tool returned a result with mismatched call identity");
            }
            if (!result.success() && (result.error() == null || result.error().isBlank())) {
                return malformedResult(call, "Tool returned a failure without an error");
            }
            return result;
        } catch (DomainException error) {
            if (ShellTool.INDETERMINATE_EXECUTION.equals(error.code())) throw error;
            return ToolResult.failure(call.id(), call.name(), errorMessage(error));
        } catch (RuntimeException error) {
            return ToolResult.failure(call.id(), call.name(), errorMessage(error));
        }
    }

    private ToolResult malformedResult(ToolCall call, String detail) {
        return ToolResult.failure(call.id(), call.name(), "Malformed tool result: " + detail);
    }

    private String errorMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
