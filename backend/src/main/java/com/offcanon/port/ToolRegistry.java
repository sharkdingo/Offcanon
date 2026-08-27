package com.offcanon.port;

import com.offcanon.agent.domain.ToolCall;
import com.offcanon.agent.domain.ToolDefinition;
import com.offcanon.agent.domain.ToolResult;
import com.offcanon.experiment.domain.Experiment;

import java.util.List;

public interface ToolRegistry {
    List<ToolDefinition> definitions();
    ToolResult dispatch(Experiment experiment, ToolCall call);
}
