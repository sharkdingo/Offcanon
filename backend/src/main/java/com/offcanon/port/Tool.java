package com.offcanon.port;

import com.offcanon.agent.domain.ToolDefinition;
import com.offcanon.agent.domain.ToolResult;
import com.offcanon.experiment.domain.Experiment;

import java.util.Map;

public interface Tool {
    ToolDefinition definition();
    ToolResult execute(Experiment experiment, String callId, Map<String, Object> arguments);
}
