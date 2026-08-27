package com.pico.agent.application;

import com.pico.agent.domain.AgentRunResult;
import com.pico.experiment.domain.Experiment;
import com.pico.infrastructure.agent.NoCancellation;
import com.pico.port.AgentLoopPort;
import com.pico.port.CancellationPort;
import com.pico.port.ExperimentRepository;
import com.pico.port.ProjectRepository;
import com.pico.port.SnapshotRepository;
import com.pico.port.VerificationPort;
import com.pico.project.domain.Project;
import com.pico.shared.domain.DomainException;
import com.pico.shared.web.NotFoundException;
import com.pico.workspace.domain.Snapshot;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AgentApplicationService {
    private final ExperimentRepository experimentRepository;
    private final ProjectRepository projectRepository;
    private final SnapshotRepository snapshotRepository;
    private final AgentLoopPort agentLoop;
    private final VerificationPort verification;
    private final Executor executor;
    private final ConcurrentHashMap<UUID, AtomicBoolean> cancellations = new ConcurrentHashMap<>();

    public AgentApplicationService(ExperimentRepository experimentRepository,
                                   ProjectRepository projectRepository,
                                   SnapshotRepository snapshotRepository,
                                   AgentLoopPort agentLoop,
                                   VerificationPort verification,
                                   Executor agentExecutor) {
        this.experimentRepository = experimentRepository;
        this.projectRepository = projectRepository;
        this.snapshotRepository = snapshotRepository;
        this.agentLoop = agentLoop;
        this.verification = verification;
        this.executor = agentExecutor;
    }

    public Experiment start(UUID experimentId) {
        Experiment experiment = get(experimentId);
        experiment.start();
        experimentRepository.save(experiment);
        AtomicBoolean cancellation = new AtomicBoolean(false);
        cancellations.put(experimentId, cancellation);
        executor.execute(() -> run(experimentId, cancellation));
        return experiment;
    }

    public Experiment cancel(UUID experimentId) {
        Experiment experiment = get(experimentId);
        AtomicBoolean token = cancellations.computeIfAbsent(experimentId, ignored -> new AtomicBoolean(false));
        token.set(true);
        if (experiment.status() == com.pico.experiment.domain.ExperimentStatus.READY_TO_RUN
                || experiment.status() == com.pico.experiment.domain.ExperimentStatus.RUNNING) {
            experiment.cancel();
            experimentRepository.save(experiment);
        }
        return experiment;
    }

    private void run(UUID experimentId, AtomicBoolean cancellation) {
        Experiment experiment = get(experimentId);
        try {
            AgentRunResult result = agentLoop.run(experiment, cancellation::get);
            experiment.markAgentCompleted(result.summary());
            experimentRepository.save(experiment);

            Project project = projectRepository.findById(experiment.projectId())
                    .orElseThrow(() -> new NotFoundException("Project not found: " + experiment.projectId()));
            Snapshot snapshot = snapshotRepository.findById(experiment.baseSnapshotId())
                    .orElseThrow(() -> new NotFoundException("Snapshot not found: " + experiment.baseSnapshotId()));
            experiment.beginVerification();
            experimentRepository.save(experiment);
            experiment.markVerified(verification.verify(project, experiment, snapshot));
            experimentRepository.save(experiment);
        } catch (DomainException error) {
            if ("AGENT_CANCELLED".equals(error.code()) && experiment.status() != com.pico.experiment.domain.ExperimentStatus.CANCELLED) {
                experiment.cancel();
            } else if (experiment.status() != com.pico.experiment.domain.ExperimentStatus.CANCELLED) {
                experiment.fail(error.code() + ": " + error.getMessage());
            }
            experimentRepository.save(experiment);
        } catch (RuntimeException error) {
            if (experiment.status() != com.pico.experiment.domain.ExperimentStatus.CANCELLED) {
                experiment.fail(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                experimentRepository.save(experiment);
            }
        } finally {
            cancellations.remove(experimentId);
        }
    }

    private Experiment get(UUID experimentId) {
        return experimentRepository.findById(experimentId)
                .orElseThrow(() -> new NotFoundException("Experiment not found: " + experimentId));
    }
}
