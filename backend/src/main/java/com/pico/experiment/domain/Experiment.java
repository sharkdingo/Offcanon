package com.pico.experiment.domain;

import com.pico.shared.domain.DomainException;
import com.pico.verification.domain.VerificationResult;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class Experiment {
    private final UUID id;
    private final UUID projectId;
    private final UUID sessionId;
    private final String task;
    private final Instant createdAt;
    private ExperimentStatus status;
    private UUID baseSnapshotId;
    private Path workspacePath;
    private String agentSummary;
    private VerificationResult verificationResult;
    private String failureReason;
    private long version;

    private Experiment(UUID id, UUID projectId, UUID sessionId, String task, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.task = Objects.requireNonNull(task, "task");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (task.isBlank()) {
            throw new IllegalArgumentException("Experiment task must not be blank");
        }
        this.status = ExperimentStatus.CREATED;
    }

    public static Experiment create(UUID projectId, UUID sessionId, String task, Instant now) {
        return new Experiment(UUID.randomUUID(), projectId, sessionId, task.trim(), now);
    }

    /** Rehydrates persisted state without replaying side effects or lifecycle events. */
    public static Experiment restore(UUID id,
                                     UUID projectId,
                                     UUID sessionId,
                                     String task,
                                     Instant createdAt,
                                     ExperimentStatus status,
                                     UUID baseSnapshotId,
                                     Path workspacePath,
                                     String agentSummary,
                                     VerificationResult verificationResult,
                                     String failureReason,
                                     long version) {
        Experiment experiment = new Experiment(id, projectId, sessionId, task, createdAt);
        experiment.status = Objects.requireNonNull(status, "status");
        experiment.baseSnapshotId = baseSnapshotId;
        experiment.workspacePath = workspacePath == null ? null : workspacePath.toAbsolutePath().normalize();
        experiment.agentSummary = agentSummary;
        experiment.verificationResult = verificationResult;
        experiment.failureReason = failureReason;
        experiment.version = version;
        return experiment;
    }

    public void beginSnapshot() {
        transition(ExperimentStatus.CREATED, ExperimentStatus.SNAPSHOTTING);
    }

    public void attachBase(UUID snapshotId, Path workspace) {
        requireStatus(ExperimentStatus.SNAPSHOTTING);
        if (baseSnapshotId != null) {
            throw new DomainException("BASE_ALREADY_ATTACHED", "Experiment base snapshot is immutable");
        }
        baseSnapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
        workspacePath = Objects.requireNonNull(workspace, "workspace").toAbsolutePath().normalize();
        status = ExperimentStatus.READY_TO_RUN;
        version++;
    }

    public void start() {
        transition(ExperimentStatus.READY_TO_RUN, ExperimentStatus.RUNNING);
    }

    public void markAgentCompleted(String summary) {
        requireStatus(ExperimentStatus.RUNNING);
        agentSummary = Objects.requireNonNull(summary, "summary").trim();
        status = ExperimentStatus.AGENT_COMPLETED;
        version++;
    }

    public void beginVerification() {
        transition(ExperimentStatus.AGENT_COMPLETED, ExperimentStatus.VERIFYING);
    }

    public void markVerified(VerificationResult result) {
        requireStatus(ExperimentStatus.VERIFYING);
        verificationResult = Objects.requireNonNull(result, "result");
        status = result.passed() ? ExperimentStatus.VERIFIED : ExperimentStatus.REJECTED;
        if (!result.passed()) {
            failureReason = result.failureReason();
        }
        version++;
    }

    public void markStale(String reason) {
        if (status != ExperimentStatus.READY_TO_RUN
                && status != ExperimentStatus.AGENT_COMPLETED
                && status != ExperimentStatus.VERIFIED
                && status != ExperimentStatus.PREPARING_PROMOTION
                && status != ExperimentStatus.PROMOTING) {
            throw new DomainException("INVALID_STALE_TRANSITION", "Experiment cannot become stale from " + status);
        }
        failureReason = reason;
        status = ExperimentStatus.STALE;
        version++;
    }

    public void markRecoveryRequired(String reason) {
        if (status != ExperimentStatus.PROMOTING) {
            throw new DomainException("INVALID_RECOVERY_TRANSITION", "Recovery is only required during promotion");
        }
        failureReason = Objects.requireNonNull(reason, "reason");
        status = ExperimentStatus.RECOVERY_REQUIRED;
        version++;
    }

    public void beginPromotion() {
        transition(ExperimentStatus.VERIFIED, ExperimentStatus.PREPARING_PROMOTION);
    }

    public void markPromoting() {
        transition(ExperimentStatus.PREPARING_PROMOTION, ExperimentStatus.PROMOTING);
    }

    public void markPromoted() {
        transition(ExperimentStatus.PROMOTING, ExperimentStatus.PROMOTED);
    }

    public void cancel() {
        if (status == ExperimentStatus.CANCELLED) {
            return;
        }
        if (status != ExperimentStatus.READY_TO_RUN
                && status != ExperimentStatus.RUNNING
                && status != ExperimentStatus.AGENT_COMPLETED
                && status != ExperimentStatus.VERIFYING) {
            throw new DomainException("INVALID_CANCEL", "Terminal experiment cannot be cancelled");
        }
        status = ExperimentStatus.CANCELLED;
        version++;
    }

    public void fail(String reason) {
        if (status != ExperimentStatus.CREATED
                && status != ExperimentStatus.SNAPSHOTTING
                && status != ExperimentStatus.READY_TO_RUN
                && status != ExperimentStatus.RUNNING
                && status != ExperimentStatus.AGENT_COMPLETED
                && status != ExperimentStatus.VERIFYING
                && status != ExperimentStatus.PREPARING_PROMOTION
                && status != ExperimentStatus.PROMOTING) {
            throw new DomainException("INVALID_FAILURE", "Experiment cannot be failed from " + status);
        }
        failureReason = Objects.requireNonNull(reason, "reason");
        status = ExperimentStatus.FAILED;
        version++;
    }

    private void transition(ExperimentStatus expected, ExperimentStatus next) {
        requireStatus(expected);
        status = next;
        version++;
    }

    private void requireStatus(ExperimentStatus expected) {
        if (status != expected) {
            throw new DomainException("INVALID_EXPERIMENT_TRANSITION",
                    "Expected " + expected + " but was " + status);
        }
    }

    public UUID id() { return id; }
    public UUID projectId() { return projectId; }
    public UUID sessionId() { return sessionId; }
    public String task() { return task; }
    public Instant createdAt() { return createdAt; }
    public ExperimentStatus status() { return status; }
    public UUID baseSnapshotId() { return baseSnapshotId; }
    public Path workspacePath() { return workspacePath; }
    public String agentSummary() { return agentSummary; }
    public VerificationResult verificationResult() { return verificationResult; }
    public String failureReason() { return failureReason; }
    public long version() { return version; }
}
