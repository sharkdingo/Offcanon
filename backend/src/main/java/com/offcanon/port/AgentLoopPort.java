package com.offcanon.port;

import com.offcanon.agent.domain.AgentRunResult;
import com.offcanon.agent.domain.AgentRunSettings;
import com.offcanon.agent.domain.SessionContext;
import com.offcanon.experiment.domain.Experiment;

import java.util.Optional;

public interface AgentLoopPort {
    AgentRunResult run(Experiment experiment, CancellationPort cancellation);

    default AgentRunResult run(Experiment experiment,
                               CancellationPort cancellation,
                               Optional<SessionContext> sessionContext) {
        return run(experiment, cancellation);
    }

    default AgentRunResult run(Experiment experiment,
                               CancellationPort cancellation,
                               Optional<SessionContext> sessionContext,
                               AgentRunSettings settings) {
        return run(experiment, cancellation, sessionContext);
    }
}
