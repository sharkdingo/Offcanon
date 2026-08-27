package com.pico.port;

import com.pico.agent.domain.AgentRunResult;
import com.pico.agent.domain.SessionContext;
import com.pico.experiment.domain.Experiment;

import java.util.Optional;

public interface AgentLoopPort {
    AgentRunResult run(Experiment experiment, CancellationPort cancellation);

    default AgentRunResult run(Experiment experiment,
                               CancellationPort cancellation,
                               Optional<SessionContext> sessionContext) {
        return run(experiment, cancellation);
    }
}
