package com.offcanon.experiment.domain;

import com.offcanon.shared.domain.DomainException;
import com.offcanon.verification.domain.VerificationResult;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class Experiment {
    /**
     * Keep the durable summary bounded even when the model uses multi-byte
     * text. The full bounded context remains an
     * audit concern; this field is the user-facing lifecycle summary.
     */
    private static final int MAX_AGENT_SUMMARY_CHARS = 12_000;
    private static final String SUMMARY_TRUNCATION_MARKER = "\n...[summary truncated]...";
    private final UUID id;
    private final UUID projectId;
    private final UUID sessionId;
    private final UUID continuedFromExperimentId;
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

    private Experiment(UUID id,
                       UUID projectId,
                       UUID sessionId,
                       UUID continuedFromExperimentId,
                       String task,
                       Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.continuedFromExperimentId = continuedFromExperimentId;
        if (id.equals(continuedFromExperimentId)) {
            throw new IllegalArgumentException("Experiment cannot continue from itself");
        }
        this.task = Objects.requireNonNull(task, "task");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (task.isBlank()) {
            throw new IllegalArgumentException("Experiment task must not be blank");
        }
        this.status = ExperimentStatus.CREATED;
    }

    public static Experiment create(UUID projectId, UUID sessionId, String task, Instant now) {
        return new Experiment(UUID.randomUUID(), projectId, sessionId, null, task.trim(), now);
    }

    public static Experiment continueFrom(UUID projectId,
                                          UUID sessionId,
                                          UUID previousExperimentId,
                                          String task,
                                          Instant now) {
        return new Experiment(UUID.randomUUID(), projectId, sessionId,
                Objects.requireNonNull(previousExperimentId, "previousExperimentId"), task.trim(), now);
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
        return restore(id, projectId, sessionId, null, task, createdAt, status, baseSnapshotId,
                resultSnapshotId, workspacePath, agentSummary, verificationResult, failureReason, version);
    }

    public static Experiment restore(UUID id,
                                     UUID projectId,
                                     UUID sessionId,
                                     UUID continuedFromExperimentId,
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
        Experiment experiment = new Experiment(id, projectId, sessionId, continuedFromExperimentId, task, createdAt);
        experiment.status = Objects.requireNonNull(status, "status");
        experiment.baseSnapshotId = baseSnapshotId;
        experiment.resultSnapshotId = resultSnapshotId;
        experiment.workspacePath = workspacePath == null ? null : workspacePath.toAbsolutePath().normalize();
        experiment.agentSummary = agentSummary;
        experiment.verificationResult = verificationResult;
        experiment.failureReason = failureReason;
        experiment.version = version;
        validateRestoredBindings(experiment);
        return experiment;
    }

    private static void validateRestoredBindings(Experiment experiment) {
        if (experiment.resultSnapshotId != null && experiment.baseSnapshotId == null) {
            throw new IllegalArgumentException("A result snapshot requires a base snapshot");
        }
        if (experiment.workspacePath != null && experiment.baseSnapshotId == null) {
            throw new IllegalArgumentException("An experiment workspace requires a base snapshot");
        }
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
        String normalized = Objects.requireNonNull(summary, "summary").trim();
        if (normalized.length() > MAX_AGENT_SUMMARY_CHARS) {
            int contentLimit = Math.max(0, MAX_AGENT_SUMMARY_CHARS - SUMMARY_TRUNCATION_MARKER.length());
            normalized = normalized.substring(0, contentLimit) + SUMMARY_TRUNCATION_MARKER;
        }
        agentSummary = normalized;
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

    /**
     * Resolves a durable promotion-recovery journal after the canonical tree has
     * been independently proven to match its candidate. The wider input set is
     * intentional: persistence may have failed before the lifecycle marker caught
     * up with the already-durable journal.
     */
    public void reconcilePromotionCommitted() {
        if (status == ExperimentStatus.PROMOTED) return;
        if (status != ExperimentStatus.VERIFIED
                && status != ExperimentStatus.PREPARING_PROMOTION
                && status != ExperimentStatus.PROMOTING
                && status != ExperimentStatus.RECOVERY_REQUIRED) {
            throw new DomainException("PROMOTION_STATE_MISMATCH",
                    "Cannot reconcile committed promotion from " + status);
        }
        failureReason = null;
        status = ExperimentStatus.PROMOTED;
        version++;
    }

    /** Resolves a recovery journal after the canonical tree is proven unchanged. */
    public void reconcilePromotionAborted() {
        if (status == ExperimentStatus.VERIFIED || status == ExperimentStatus.STALE) return;
        if (status != ExperimentStatus.PREPARING_PROMOTION
                && status != ExperimentStatus.PROMOTING
                && status != ExperimentStatus.RECOVERY_REQUIRED) {
            throw new DomainException("PROMOTION_STATE_MISMATCH",
                    "Cannot reconcile aborted promotion from " + status);
        }
        failureReason = null;
        status = ExperimentStatus.VERIFIED;
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
    public UUID continuedFromExperimentId() { return continuedFromExperimentId; }
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
