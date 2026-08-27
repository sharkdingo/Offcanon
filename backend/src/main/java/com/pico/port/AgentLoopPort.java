package com.pico.port;

import com.pico.agent.domain.AgentRunResult;
import com.pico.experiment.domain.Experiment;

public interface AgentLoopPort {
    AgentRunResult run(Experiment experiment, CancellationPort cancellation);
}
