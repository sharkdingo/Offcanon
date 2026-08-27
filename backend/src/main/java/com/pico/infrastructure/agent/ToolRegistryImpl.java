package com.pico.infrastructure.agent;

import com.pico.agent.domain.ToolCall;
import com.pico.agent.domain.ToolDefinition;
import com.pico.agent.domain.ToolResult;
import com.pico.experiment.domain.Experiment;
import com.pico.port.Tool;
import com.pico.port.ToolRegistry;
import com.pico.shared.domain.DomainException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class ToolRegistryImpl implements ToolRegistry {
    private final Map<String, Tool> tools;
    private final List<ToolDefinition> definitions;

    public ToolRegistryImpl(List<Tool> discoveredTools) {
        Map<String, Tool> byName = new LinkedHashMap<>();
        for (Tool tool : discoveredTools) {
            String name = tool.definition().name();
            if (byName.putIfAbsent(name, tool) != null) {
                throw new IllegalStateException("Duplicate tool: " + name);
            }
        }
        this.tools = Collections.unmodifiableMap(new LinkedHashMap<>(byName));
        this.definitions = this.tools.values().stream().map(Tool::definition).toList();
    }

    @Override
    public List<ToolDefinition> definitions() {
        return definitions;
    }

    @Override
    public ToolResult dispatch(Experiment experiment, ToolCall call) {
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return ToolResult.failure(call.id(), call.name(), "Unknown tool: " + call.name());
        }
        try {
            return tool.execute(experiment, call.id(), call.arguments());
        } catch (DomainException error) {
            if (ShellTool.INDETERMINATE_EXECUTION.equals(error.code())) throw error;
            return ToolResult.failure(call.id(), call.name(), error.getMessage());
        } catch (RuntimeException error) {
            return ToolResult.failure(call.id(), call.name(), error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }
}
