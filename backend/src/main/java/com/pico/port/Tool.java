package com.pico.port;

import com.pico.agent.domain.ToolDefinition;
import com.pico.agent.domain.ToolResult;
import com.pico.experiment.domain.Experiment;

import java.util.Map;

public interface Tool {
    ToolDefinition definition();
    ToolResult execute(Experiment experiment, String callId, Map<String, Object> arguments);
}
