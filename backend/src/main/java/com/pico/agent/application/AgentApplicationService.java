package com.pico.agent.application;

import com.pico.agent.domain.AgentRunResult;
import com.pico.agent.domain.SessionContext;
import com.pico.experiment.domain.Experiment;
import com.pico.experiment.domain.ExperimentStatus;
import com.pico.port.AgentLoopPort;
import com.pico.port.ExperimentRepository;
import com.pico.port.EventSink;
import com.pico.port.ProjectRepository;
import com.pico.port.SnapshotRepository;
import com.pico.port.SnapshotPort;
import com.pico.port.SessionRunLeasePort;
import com.pico.port.VerificationPort;
import com.pico.port.WorkspacePort;
import com.pico.project.domain.Project;
import com.pico.shared.domain.DomainException;
import com.pico.shared.web.NotFoundException;
import com.pico.workspace.domain.Snapshot;
import com.pico.verification.domain.VerificationPurpose;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.Comparator;
import java.util.Optional;
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
    private final SnapshotPort snapshotPort;
    private final AgentLoopPort agentLoop;
    private final VerificationPort verification;
    private final ExecutorService executor;
    private final EventSink events;
    private final SessionRunLeasePort sessionRunLease;
    private final WorkspacePort workspaces;
    private final ConcurrentHashMap<UUID, AtomicBoolean> cancellations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Future<?>> runs = new ConcurrentHashMap<>();

    public AgentApplicationService(ExperimentRepository experimentRepository,
                                   ProjectRepository projectRepository,
                                   SnapshotRepository snapshotRepository,
                                   SnapshotPort snapshotPort,
                                   AgentLoopPort agentLoop,
                                   VerificationPort verification,
                                   ExecutorService agentExecutor,
                                   EventSink events,
                                   SessionRunLeasePort sessionRunLease,
                                   WorkspacePort workspaces) {
        this.experimentRepository = experimentRepository;
        this.projectRepository = projectRepository;
        this.snapshotRepository = snapshotRepository;
        this.snapshotPort = snapshotPort;
        this.agentLoop = agentLoop;
        this.verification = verification;
        this.executor = agentExecutor;
        this.events = events;
        this.sessionRunLease = sessionRunLease;
        this.workspaces = workspaces;
    }

    public Experiment start(UUID experimentId) {
        Experiment experiment = get(experimentId);
        if (experiment.status() != ExperimentStatus.READY_TO_RUN) return experiment;
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
        publishBestEffort(experimentId, "EXPERIMENT_STARTED", java.util.Map.of("status", experiment.status().name()));
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
            publishBestEffort(experimentId, "EXPERIMENT_CANCELLED", java.util.Map.of("status", experiment.status().name()));
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
            publishBestEffort(experimentId, "EXPERIMENT_CANCELLED", java.util.Map.of("status", experiment.status().name()));
        }
        return experiment;
    }

    private void run(UUID experimentId, AtomicBoolean cancellation) {
        Experiment experiment = get(experimentId);
        try {
            AgentRunResult result = agentLoop.run(experiment, cancellation::get, sessionContext(experiment));
            experiment.markAgentCompleted(result.summary());
            experimentRepository.save(experiment);
            if (cancellation.get()) {
                experiment.cancel();
                experimentRepository.save(experiment);
                publishBestEffort(experimentId, "EXPERIMENT_CANCELLED", java.util.Map.of("status", experiment.status().name()));
                return;
            }

            Project project = projectRepository.findById(experiment.projectId())
                    .orElseThrow(() -> new NotFoundException("Project not found: " + experiment.projectId()));
            Snapshot snapshot = snapshotRepository.findById(experiment.baseSnapshotId())
                    .orElseThrow(() -> new NotFoundException("Snapshot not found: " + experiment.baseSnapshotId()));
            Snapshot resultSnapshot = snapshotPort.captureWorkspace(project, experiment.workspacePath(), snapshot.fingerprint());
            snapshotRepository.save(resultSnapshot);
            experiment.sealResult(resultSnapshot.id());
            experimentRepository.save(experiment);
            publishBestEffort(experimentId, "RESULT_SNAPSHOT_SEALED", java.util.Map.of(
                    "snapshotId", resultSnapshot.id().toString(),
                    "fingerprint", resultSnapshot.fingerprint()));
            if (cancellation.get()) {
                experiment.cancel();
                experimentRepository.save(experiment);
                publishBestEffort(experimentId, "EXPERIMENT_CANCELLED", java.util.Map.of("status", experiment.status().name()));
                return;
            }
            experiment.beginVerification();
            experimentRepository.save(experiment);
            publishBestEffort(experimentId, "VERIFICATION_STARTED", java.util.Map.of("status", experiment.status().name()));
            java.nio.file.Path verificationWorkspace = workspaces.createVerificationWorkspace(resultSnapshot, experiment);
            var verificationResult = verification.verify(project, experiment, resultSnapshot,
                    verificationWorkspace, VerificationPurpose.EXPERIMENT_RESULT);
            String verifiedFingerprint = snapshotPort.fingerprintWorkspace(project, verificationWorkspace, snapshot.fingerprint());
            if (!resultSnapshot.fingerprint().equals(verifiedFingerprint)) {
                throw new DomainException("VERIFICATION_MUTATED_SOURCE",
                        "Trusted verification changed promotion-relevant files");
            }
            String sealedFingerprint = snapshotPort.fingerprintWorkspace(project,
                    resultSnapshot.materializedPath(), snapshot.fingerprint());
            if (!resultSnapshot.fingerprint().equals(sealedFingerprint)) {
                throw new DomainException("RESULT_SNAPSHOT_MUTATED", "Sealed result snapshot changed after capture");
            }
            if (cancellation.get()) {
                experiment.cancel();
                experimentRepository.save(experiment);
                publishBestEffort(experimentId, "EXPERIMENT_CANCELLED", java.util.Map.of("status", experiment.status().name()));
                return;
            }
            experiment.markVerified(verificationResult);
            experimentRepository.save(experiment);
            publishBestEffort(experimentId, "VERIFICATION_FINISHED", java.util.Map.of("status", experiment.status().name(), "passed", experiment.status().name().equals("VERIFIED")));
        } catch (DomainException error) {
            if (cancellation.get() || "AGENT_CANCELLED".equals(error.code())) {
                if (experiment.status() != ExperimentStatus.CANCELLED) {
                    experiment.cancel();
                    experimentRepository.save(experiment);
                }
                publishBestEffort(experimentId, "EXPERIMENT_CANCELLED", java.util.Map.of("status", experiment.status().name()));
                return;
            }
            if (experiment.status() != ExperimentStatus.CANCELLED) {
                experiment.fail(error.code() + ": " + error.getMessage());
                experimentRepository.save(experiment);
            }
            publishBestEffort(experimentId, "EXPERIMENT_FAILED", java.util.Map.of("code", error.code(), "message", error.getMessage() == null ? "" : error.getMessage()));
        } catch (RuntimeException error) {
            if (cancellation.get()) {
                if (experiment.status() != ExperimentStatus.CANCELLED) {
                    experiment.cancel();
                    experimentRepository.save(experiment);
                }
                publishBestEffort(experimentId, "EXPERIMENT_CANCELLED", java.util.Map.of("status", experiment.status().name()));
                return;
            }
            if (experiment.status() != ExperimentStatus.CANCELLED) {
                experiment.fail(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                experimentRepository.save(experiment);
                publishBestEffort(experimentId, "EXPERIMENT_FAILED", java.util.Map.of("message", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
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

    private Optional<SessionContext> sessionContext(Experiment current) {
        return experimentRepository.findBySessionId(current.sessionId()).stream()
                .filter(candidate -> !candidate.id().equals(current.id()))
                .filter(candidate -> candidate.createdAt().isBefore(current.createdAt()))
                .filter(candidate -> candidate.baseSnapshotId() != null)
                .filter(candidate -> candidate.agentSummary() != null && !candidate.agentSummary().isBlank())
                .max(Comparator.comparing(Experiment::createdAt).thenComparing(Experiment::id))
                .map(previous -> new SessionContext(previous.id(), previous.baseSnapshotId(),
                        previous.task(), previous.agentSummary()));
    }

    private void publishBestEffort(UUID experimentId, String type, java.util.Map<String, Object> payload) {
        try {
            events.publish(experimentId, type, payload);
        } catch (RuntimeException ignored) {
            // Telemetry outage must not change the execution state.
        }
    }
}
