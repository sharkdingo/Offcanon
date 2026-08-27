package com.pico.agent.domain;

import java.util.List;

public record AgentRunResult(String summary, int steps, String terminationReason, List<ModelMessage> context) {
    public AgentRunResult {
        context = List.copyOf(context);
    }
}
