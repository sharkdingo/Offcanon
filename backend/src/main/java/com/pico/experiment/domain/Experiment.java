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
    private UUID resultSnapshotId;
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
                                     UUID resultSnapshotId,
                                     Path workspacePath,
                                     String agentSummary,
                                     VerificationResult verificationResult,
                                     String failureReason,
                                     long version) {
        Experiment experiment = new Experiment(id, projectId, sessionId, task, createdAt);
        experiment.status = Objects.requireNonNull(status, "status");
        experiment.baseSnapshotId = baseSnapshotId;
        experiment.resultSnapshotId = resultSnapshotId;
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

    public void sealResult(UUID snapshotId) {
        requireStatus(ExperimentStatus.AGENT_COMPLETED);
        if (resultSnapshotId != null) {
            throw new DomainException("RESULT_ALREADY_SEALED", "Experiment result snapshot is immutable");
        }
        resultSnapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
        version++;
    }

    public void beginVerification() {
        if (resultSnapshotId == null) {
            throw new DomainException("RESULT_SNAPSHOT_MISSING", "Seal the experiment result before verification");
        }
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

    public void rejectVerifiedPromotion(VerificationResult result) {
        requireStatus(ExperimentStatus.VERIFIED);
        verificationResult = Objects.requireNonNull(result, "result");
        failureReason = result.failureReason();
        status = ExperimentStatus.REJECTED;
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
        if (resultSnapshotId == null) {
            throw new DomainException("RESULT_SNAPSHOT_MISSING", "Verified experiment has no immutable result");
        }
        transition(ExperimentStatus.VERIFIED, ExperimentStatus.PREPARING_PROMOTION);
    }

    public void rejectPromotion(VerificationResult result) {
        requireStatus(ExperimentStatus.PREPARING_PROMOTION);
        if (result.passed()) {
            throw new DomainException("INVALID_PROMOTION_REJECTION", "Passing verification cannot reject promotion");
        }
        verificationResult = Objects.requireNonNull(result, "result");
        failureReason = result.failureReason();
        status = ExperimentStatus.REJECTED;
        version++;
    }

    public void abortPromotion(String reason) {
        requireStatus(ExperimentStatus.PREPARING_PROMOTION);
        failureReason = Objects.requireNonNull(reason, "reason");
        status = ExperimentStatus.VERIFIED;
        version++;
    }

    /** Restore a promotion that was interrupted before any canonical change was observed. */
    public void recoverPromotion() {
        if (status != ExperimentStatus.PROMOTING && status != ExperimentStatus.RECOVERY_REQUIRED) {
            throw new DomainException("INVALID_RECOVERY_TRANSITION", "Promotion cannot be restored from " + status);
        }
        failureReason = null;
        status = ExperimentStatus.VERIFIED;
        version++;
    }

    public void recoverPromotionCommitted() {
        if (status == ExperimentStatus.PROMOTED) return;
        if (status != ExperimentStatus.PROMOTING && status != ExperimentStatus.RECOVERY_REQUIRED) {
            throw new DomainException("INVALID_RECOVERY_TRANSITION", "Promotion cannot be committed from " + status);
        }
        failureReason = null;
        status = ExperimentStatus.PROMOTED;
        version++;
    }

    public void requirePromotionRecovery(String reason) {
        if (status != ExperimentStatus.VERIFIED
                && status != ExperimentStatus.PREPARING_PROMOTION
                && status != ExperimentStatus.PROMOTING
                && status != ExperimentStatus.PROMOTED
                && status != ExperimentStatus.RECOVERY_REQUIRED) {
            throw new DomainException("INVALID_RECOVERY_TRANSITION", "Promotion recovery is not applicable from " + status);
        }
        failureReason = Objects.requireNonNull(reason, "reason");
        if (status != ExperimentStatus.RECOVERY_REQUIRED) {
            status = ExperimentStatus.RECOVERY_REQUIRED;
            version++;
        }
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
    public UUID resultSnapshotId() { return resultSnapshotId; }
    public Path workspacePath() { return workspacePath; }
    public String agentSummary() { return agentSummary; }
    public VerificationResult verificationResult() { return verificationResult; }
    public String failureReason() { return failureReason; }
    public long version() { return version; }
}
