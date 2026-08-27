package com.pico.port;

import com.pico.agent.domain.ToolCall;
import com.pico.agent.domain.ToolDefinition;
import com.pico.agent.domain.ToolResult;
import com.pico.experiment.domain.Experiment;

import java.util.List;

public interface ToolRegistry {
    List<ToolDefinition> definitions();
    ToolResult dispatch(Experiment experiment, ToolCall call);
}
