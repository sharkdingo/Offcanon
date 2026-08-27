package com.pico.agent.application;

import com.pico.agent.domain.AgentRunResult;
import com.pico.experiment.domain.Experiment;
import com.pico.experiment.domain.ExperimentStatus;
import com.pico.port.AgentLoopPort;
import com.pico.port.ExperimentRepository;
import com.pico.port.EventSink;
import com.pico.port.ProjectRepository;
import com.pico.port.SnapshotRepository;
import com.pico.port.SessionRunLeasePort;
import com.pico.port.VerificationPort;
import com.pico.project.domain.Project;
import com.pico.shared.domain.DomainException;
import com.pico.shared.web.NotFoundException;
import com.pico.workspace.domain.Snapshot;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AgentApplicationService {
    private final ExperimentRepository experimentRepository;
    private final ProjectRepository projectRepository;
    private final SnapshotRepository snapshotRepository;
    private final AgentLoopPort agentLoop;
    private final VerificationPort verification;
    private final ExecutorService executor;
    private final EventSink events;
    private final SessionRunLeasePort sessionRunLease;
    private final ConcurrentHashMap<UUID, AtomicBoolean> cancellations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Future<?>> runs = new ConcurrentHashMap<>();

    public AgentApplicationService(ExperimentRepository experimentRepository,
                                   ProjectRepository projectRepository,
                                   SnapshotRepository snapshotRepository,
                                   AgentLoopPort agentLoop,
                                   VerificationPort verification,
                                   ExecutorService agentExecutor,
                                   EventSink events,
                                   SessionRunLeasePort sessionRunLease) {
        this.experimentRepository = experimentRepository;
        this.projectRepository = projectRepository;
        this.snapshotRepository = snapshotRepository;
        this.agentLoop = agentLoop;
        this.verification = verification;
        this.executor = agentExecutor;
        this.events = events;
        this.sessionRunLease = sessionRunLease;
    }

    public Experiment start(UUID experimentId) {
        Experiment experiment = get(experimentId);
        if (experiment.status() != ExperimentStatus.READY_TO_RUN) {
            if (experiment.status() == ExperimentStatus.RUNNING
                    || experiment.status() == ExperimentStatus.AGENT_COMPLETED
                    || experiment.status() == ExperimentStatus.VERIFYING
                    || experiment.status() == ExperimentStatus.VERIFIED
                    || experiment.status() == ExperimentStatus.REJECTED
                    || experiment.status() == ExperimentStatus.FAILED
                    || experiment.status() == ExperimentStatus.CANCELLED
                    || experiment.status() == ExperimentStatus.STALE
                    || experiment.status() == ExperimentStatus.PREPARING_PROMOTION
                    || experiment.status() == ExperimentStatus.PROMOTING
                    || experiment.status() == ExperimentStatus.PROMOTED
                    || experiment.status() == ExperimentStatus.RECOVERY_REQUIRED) {
                return experiment;
            }
            throw new DomainException("EXPERIMENT_NOT_READY", "Experiment is not ready to start");
        }
        if (!sessionRunLease.tryAcquire(experiment.sessionId(), experiment.id())) {
            throw new DomainException("SESSION_ALREADY_RUNNING", "A session can run only one experiment at a time");
        }
        try {
            experiment.start();
            experimentRepository.save(experiment);
        } catch (RuntimeException error) {
            sessionRunLease.release(experiment.sessionId(), experiment.id());
            throw error;
        }
        AtomicBoolean cancellation = new AtomicBoolean(false);
        cancellations.put(experimentId, cancellation);
        events.publish(experimentId, "EXPERIMENT_STARTED", java.util.Map.of("status", experiment.status().name()));
        AtomicBoolean enteredWorker = new AtomicBoolean(false);
        FutureTask<Void> run = new FutureTask<>(() -> {
            enteredWorker.set(true);
            try {
                run(experimentId, cancellation);
                return null;
            } finally {
                cleanupRun(experiment, cancellation);
            }
        }) {
            @Override
            protected void done() {
                if (!enteredWorker.get()) {
                    cleanupRun(experiment, cancellation);
                }
            }
        };
        if (runs.putIfAbsent(experimentId, run) != null) {
            cancellations.remove(experimentId, cancellation);
            experiment.fail("EXPERIMENT_ALREADY_RUNNING");
            experimentRepository.save(experiment);
            sessionRunLease.release(experiment.sessionId(), experiment.id());
            throw new DomainException("EXPERIMENT_ALREADY_RUNNING", "An agent run is already scheduled for this experiment");
        }
        try {
            executor.execute(run);
        } catch (RuntimeException error) {
            runs.remove(experimentId, run);
            cancellations.remove(experimentId, cancellation);
            experiment.fail("AGENT_EXECUTOR_REJECTED: " + (error.getMessage() == null ? "executor unavailable" : error.getMessage()));
            experimentRepository.save(experiment);
            sessionRunLease.release(experiment.sessionId(), experiment.id());
            throw error;
        }
        return experiment;
    }

    public Experiment cancel(UUID experimentId) {
        Experiment experiment = get(experimentId);
        if (experiment.status() != ExperimentStatus.READY_TO_RUN
                && experiment.status() != ExperimentStatus.RUNNING
                && experiment.status() != ExperimentStatus.AGENT_COMPLETED
                && experiment.status() != ExperimentStatus.VERIFYING) {
            return experiment;
        }
        if (experiment.status() == ExperimentStatus.READY_TO_RUN) {
            experiment.cancel();
            experimentRepository.save(experiment);
            events.publish(experimentId, "EXPERIMENT_CANCELLED", java.util.Map.of("status", experiment.status().name()));
            return experiment;
        }
        AtomicBoolean token = cancellations.computeIfAbsent(experimentId, ignored -> new AtomicBoolean(false));
        token.set(true);
        Future<?> run = runs.get(experimentId);
        if (run != null) {
            run.cancel(true);
        } else {
            cancellations.remove(experimentId, token);
            sessionRunLease.release(experiment.sessionId(), experiment.id());
        }
        if (experiment.status() == ExperimentStatus.READY_TO_RUN
                || experiment.status() == ExperimentStatus.RUNNING
                || experiment.status() == ExperimentStatus.AGENT_COMPLETED
                || experiment.status() == ExperimentStatus.VERIFYING) {
            experiment.cancel();
            experimentRepository.save(experiment);
        }
        if (run == null) {
            events.publish(experimentId, "EXPERIMENT_CANCELLED", java.util.Map.of("status", experiment.status().name()));
        }
        return experiment;
    }

    private void run(UUID experimentId, AtomicBoolean cancellation) {
        Experiment experiment = get(experimentId);
        try {
            AgentRunResult result = agentLoop.run(experiment, cancellation::get);
            experiment.markAgentCompleted(result.summary());
            experimentRepository.save(experiment);
            if (cancellation.get()) {
                experiment.cancel();
                experimentRepository.save(experiment);
                events.publish(experimentId, "EXPERIMENT_CANCELLED", java.util.Map.of("status", experiment.status().name()));
                return;
            }

            Project project = projectRepository.findById(experiment.projectId())
                    .orElseThrow(() -> new NotFoundException("Project not found: " + experiment.projectId()));
            Snapshot snapshot = snapshotRepository.findById(experiment.baseSnapshotId())
                    .orElseThrow(() -> new NotFoundException("Snapshot not found: " + experiment.baseSnapshotId()));
            experiment.beginVerification();
            experimentRepository.save(experiment);
            events.publish(experimentId, "VERIFICATION_STARTED", java.util.Map.of("status", experiment.status().name()));
            var verificationResult = verification.verify(project, experiment, snapshot);
            if (cancellation.get()) {
                experiment.cancel();
                experimentRepository.save(experiment);
                events.publish(experimentId, "EXPERIMENT_CANCELLED", java.util.Map.of("status", experiment.status().name()));
                return;
            }
            experiment.markVerified(verificationResult);
            experimentRepository.save(experiment);
            events.publish(experimentId, "VERIFICATION_FINISHED", java.util.Map.of("status", experiment.status().name(), "passed", experiment.status().name().equals("VERIFIED")));
        } catch (DomainException error) {
            if (cancellation.get() || "AGENT_CANCELLED".equals(error.code())) {
                if (experiment.status() != ExperimentStatus.CANCELLED) {
                    experiment.cancel();
                    experimentRepository.save(experiment);
                }
                events.publish(experimentId, "EXPERIMENT_CANCELLED", java.util.Map.of("status", experiment.status().name()));
                return;
            }
            if (experiment.status() != ExperimentStatus.CANCELLED) {
                experiment.fail(error.code() + ": " + error.getMessage());
                experimentRepository.save(experiment);
            }
            events.publish(experimentId, "EXPERIMENT_FAILED", java.util.Map.of("code", error.code(), "message", error.getMessage() == null ? "" : error.getMessage()));
        } catch (RuntimeException error) {
            if (cancellation.get()) {
                if (experiment.status() != ExperimentStatus.CANCELLED) {
                    experiment.cancel();
                    experimentRepository.save(experiment);
                }
                events.publish(experimentId, "EXPERIMENT_CANCELLED", java.util.Map.of("status", experiment.status().name()));
                return;
            }
            if (experiment.status() != ExperimentStatus.CANCELLED) {
                experiment.fail(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                experimentRepository.save(experiment);
                events.publish(experimentId, "EXPERIMENT_FAILED", java.util.Map.of("message", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
            }
        }
    }

    private void cleanupRun(Experiment experiment, AtomicBoolean cancellation) {
        cancellations.remove(experiment.id(), cancellation);
        runs.remove(experiment.id());
        sessionRunLease.release(experiment.sessionId(), experiment.id());
    }

    private Experiment get(UUID experimentId) {
        return experimentRepository.findById(experimentId)
                .orElseThrow(() -> new NotFoundException("Experiment not found: " + experimentId));
    }
}
