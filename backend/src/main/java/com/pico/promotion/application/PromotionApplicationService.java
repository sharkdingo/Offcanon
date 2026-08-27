package com.pico.promotion.application;

import com.pico.experiment.domain.Experiment;
import com.pico.port.ExperimentRepository;
import com.pico.port.EventSink;
import com.pico.port.PromotionLockPort;
import com.pico.port.PromotionPort;
import com.pico.port.ProjectRepository;
import com.pico.port.SnapshotPort;
import com.pico.port.SnapshotRepository;
import com.pico.port.WorkspacePort;
import com.pico.port.VerificationPort;
import com.pico.port.PromotionJournalPort;
import com.pico.experiment.domain.ExperimentStatus;
import com.pico.project.domain.Project;
import com.pico.shared.domain.DomainException;
import com.pico.shared.web.NotFoundException;
import com.pico.workspace.domain.Snapshot;
import com.pico.verification.domain.VerificationPurpose;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class PromotionApplicationService {
    private final ExperimentRepository experiments;
    private final ProjectRepository projects;
    private final SnapshotRepository snapshots;
    private final SnapshotPort snapshotPort;
    private final WorkspacePort workspaces;
    private final PromotionPort promotionPort;
    private final PromotionLockPort promotionLock;
    private final EventSink events;
    private final VerificationPort verification;
    private final PromotionJournalPort journals;
    private final PromotionStateCoordinator states;
    private final String ownerId = UUID.randomUUID().toString();

    @Autowired
    public PromotionApplicationService(ExperimentRepository experiments,
                                       ProjectRepository projects,
                                       SnapshotRepository snapshots,
                                       SnapshotPort snapshotPort,
                                       WorkspacePort workspaces,
                                       PromotionPort promotionPort,
                                       PromotionLockPort promotionLock,
                                       EventSink events,
                                       VerificationPort verification,
                                       PromotionJournalPort journals,
                                       PromotionStateCoordinator states) {
        this.experiments = experiments;
        this.projects = projects;
        this.snapshots = snapshots;
        this.snapshotPort = snapshotPort;
        this.workspaces = workspaces;
        this.promotionPort = promotionPort;
        this.promotionLock = promotionLock;
        this.events = events;
        this.verification = verification;
        this.journals = journals;
        this.states = states;
    }

    public PromotionApplicationService(ExperimentRepository experiments,
                                       ProjectRepository projects,
                                       SnapshotRepository snapshots,
                                       SnapshotPort snapshotPort,
                                       WorkspacePort workspaces,
                                       PromotionPort promotionPort,
                                       PromotionLockPort promotionLock,
                                       EventSink events,
                                       VerificationPort verification,
                                       PromotionJournalPort journals) {
        this(experiments, projects, snapshots, snapshotPort, workspaces, promotionPort, promotionLock,
                events, verification, journals, new PromotionStateCoordinator(experiments, journals,
                        (org.springframework.transaction.PlatformTransactionManager) null));
    }

    public PromotionOutcome promote(UUID experimentId) {
        Experiment experiment = experiments.findById(experimentId)
                .orElseThrow(() -> new NotFoundException("Experiment not found: " + experimentId));
        Project project = projects.findById(experiment.projectId())
                .orElseThrow(() -> new NotFoundException("Project not found: " + experiment.projectId()));
        if (experiment.status() == com.pico.experiment.domain.ExperimentStatus.PROMOTED) {
            return PromotionOutcome.blocked("ALREADY_PROMOTED", "Experiment has already been promoted");
        }
        if (experiment.status() != com.pico.experiment.domain.ExperimentStatus.VERIFIED) {
            return PromotionOutcome.blocked("NOT_VERIFIED", "Only a verified experiment can be promoted");
        }
        if (experiment.baseSnapshotId() == null) {
            return PromotionOutcome.blocked("BASE_SNAPSHOT_MISSING", "Verified experiment has no base snapshot");
        }
        if (experiment.resultSnapshotId() == null) {
            return PromotionOutcome.blocked("RESULT_SNAPSHOT_MISSING", "Verified experiment has no immutable result snapshot");
        }
        Snapshot base = snapshots.findById(experiment.baseSnapshotId())
                .orElseThrow(() -> new NotFoundException("Snapshot not found: " + experiment.baseSnapshotId()));
        Snapshot resultSnapshot = snapshots.findById(experiment.resultSnapshotId())
                .orElseThrow(() -> new NotFoundException("Snapshot not found: " + experiment.resultSnapshotId()));

        try {
            String preparedAgainst = snapshotPort.currentFingerprint(project);
            if (!base.fingerprint().equals(preparedAgainst)) {
                experiment.markStale("Canonical changed after this experiment started");
                experiments.save(experiment);
                publishBestEffort(experimentId, "PROMOTION_BLOCKED", java.util.Map.of("status", "STALE", "currentFingerprint", preparedAgainst));
                return PromotionOutcome.blocked("STALE", preparedAgainst);
            }
            var candidate = workspaces.createPromotionCandidate(resultSnapshot, experiment);
            String candidateFingerprint = snapshotPort.fingerprintWorkspace(project, candidate, base.fingerprint());
            if (!resultSnapshot.fingerprint().equals(candidateFingerprint)) {
                throw new DomainException("PROMOTION_CANDIDATE_MISMATCH", "Promotion candidate differs from the sealed experiment result");
            }
            publishBestEffort(experimentId, "PROMOTION_VERIFICATION_STARTED", java.util.Map.of("fingerprint", candidateFingerprint));
            var candidateVerification = verification.verify(project, experiment, resultSnapshot, candidate,
                    VerificationPurpose.PROMOTION_CANDIDATE);
            String verifiedCandidateFingerprint = snapshotPort.fingerprintWorkspace(project, candidate, base.fingerprint());
            if (!candidateFingerprint.equals(verifiedCandidateFingerprint)) {
                throw new DomainException("PROMOTION_CANDIDATE_MUTATED", "Verification changed promotion-relevant files");
            }
            if (!candidateVerification.passed()) {
                experiment.rejectVerifiedPromotion(candidateVerification);
                experiments.save(experiment);
                publishBestEffort(experimentId, "PROMOTION_BLOCKED", java.util.Map.of(
                        "status", "PROMOTION_VERIFICATION_FAILED",
                        "detail", candidateVerification.failureReason()));
                return PromotionOutcome.blocked("PROMOTION_VERIFICATION_FAILED", candidateVerification.failureReason());
            }
            PromotionPort.PromotionPlan promotionPlan = promotionPort.plan(project, base, experiment, candidate);

            var preparedJournal = journals.create(com.pico.promotion.domain.PromotionJournal.create(
                    experiment.id(), project.id(), base.fingerprint(), candidateFingerprint, candidate,
                    promotionPlan.touchedFiles(), promotionPlan.preimageHashes(), promotionPlan.postimageHashes(), ownerId,
                    Instant.now(), Instant.now().plus(Duration.ofMinutes(30))));

            try {
                return promotionLock.withProjectLock(project.id(), () -> {
                String current = snapshotPort.currentFingerprint(project);
                if (!preparedAgainst.equals(current)) {
                    states.stalePrepared(experiment, preparedJournal,
                            "Canonical changed while promotion was being prepared", Instant.now());
                    return PromotionOutcome.blocked("STALE_DURING_PROMOTION", current);
                }
                String lockedCandidateFingerprint = snapshotPort.fingerprintWorkspace(project, candidate, base.fingerprint());
                PromotionPort.PromotionPlan lockedPlan = promotionPort.plan(project, base, experiment, candidate);
                if (!candidateFingerprint.equals(lockedCandidateFingerprint) || !promotionPlan.equals(lockedPlan)) {
                    journals.markAborted(preparedJournal,
                            "Promotion candidate changed after verification and planning", Instant.now());
                    publishBestEffort(experimentId, "PROMOTION_BLOCKED", java.util.Map.of(
                            "status", "PROMOTION_CANDIDATE_MUTATED"));
                    return PromotionOutcome.blocked("PROMOTION_CANDIDATE_MUTATED",
                            "Promotion candidate changed after verification and planning");
                }
                var unresolved = journals.findUnresolvedByProject(project.id());
                if (!unresolved.isEmpty() && !unresolved.get(0).promotionId().equals(preparedJournal.promotionId())) {
                    journals.markAborted(preparedJournal,
                            "An earlier promotion journal must be reconciled first", Instant.now());
                    return PromotionOutcome.blocked("PROMOTION_RECOVERY_PENDING",
                            "Promotion " + unresolved.get(0).promotionId() + " is still "
                                    + unresolved.get(0).phase().name());
                }
                var applyingJournal = states.beginApplying(experiment, preparedJournal, Instant.now());
                publishBestEffort(experimentId, "PROMOTION_PREPARING", java.util.Map.of("status", experiment.status().name()));
                boolean canonicalUpdated = false;
                try {
                    PromotionPort.PromotionResult result = promotionPort.apply(project, base, experiment, candidate, promotionPlan);
                    canonicalUpdated = result.applied();
                    if (!result.applied()) {
                        throw new DomainException("PROMOTION_APPLY_FAILED", "Promotion adapter did not apply the candidate");
                    }
                    String finalFingerprint = snapshotPort.currentFingerprint(project);
                    if (!candidateFingerprint.equals(finalFingerprint)) {
                        throw new DomainException("MANUAL_RECOVERY_REQUIRED",
                                "Canonical changed during final apply; inspect it before another promotion");
                    }
                    states.commit(experiment, applyingJournal, finalFingerprint, Instant.now());
                    publishBestEffort(experimentId, "PROMOTED", java.util.Map.of(
                            "changedFiles", result.changedFiles(), "fingerprint", finalFingerprint));
                    return PromotionOutcome.promoted(result.changedFiles(), finalFingerprint);
                } catch (DomainException error) {
                    if (canonicalUpdated && !"MANUAL_RECOVERY_REQUIRED".equals(error.code())) {
                        error = new DomainException("MANUAL_RECOVERY_REQUIRED",
                                "Canonical was updated but final state could not be recorded: " + error.getMessage());
                    }
                    return classifyApplyFailure(project, base, preparedAgainst, candidateFingerprint,
                            experimentId, experiment, applyingJournal, error);
                } catch (RuntimeException error) {
                    DomainException failure = new DomainException(canonicalUpdated ? "MANUAL_RECOVERY_REQUIRED" : "PROMOTION_FAILED",
                            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                    return classifyApplyFailure(project, base, preparedAgainst, candidateFingerprint,
                            experimentId, experiment, applyingJournal, failure);
                }
                });
            } catch (RuntimeException error) {
                markJournalAborted(preparedJournal, "Promotion preparation did not reach canonical apply: "
                        + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
                throw error;
            }
        } catch (DomainException error) {
            return handleFailure(experimentId, experiments.findById(experimentId).orElse(experiment), error);
        } catch (RuntimeException error) {
            DomainException failure = new DomainException("PROMOTION_FAILED", error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage());
            return handleFailure(experimentId, experiments.findById(experimentId).orElse(experiment), failure);
        }
    }

    private PromotionOutcome handleFailure(UUID experimentId, Experiment experiment, DomainException error) {
        if ("EXPERIMENT_VERSION_CONFLICT".equals(error.code())) {
            Experiment current = experiments.findById(experimentId).orElse(experiment);
            return PromotionOutcome.blocked("CONCURRENT_STATE_CHANGE",
                    "Experiment is now " + current.status().name());
        }
        if (experiment.status() == com.pico.experiment.domain.ExperimentStatus.PREPARING_PROMOTION) {
            if ("STALE_DURING_PROMOTION".equals(error.code())) {
                experiment.markStale(error.getMessage());
            } else {
                experiment.abortPromotion(error.code() + ": " + error.getMessage());
            }
            experiments.save(experiment);
            publishBestEffort(experimentId, "PROMOTION_BLOCKED", java.util.Map.of("status", error.code(), "detail", error.getMessage() == null ? "" : error.getMessage()));
        } else if (experiment.status() == com.pico.experiment.domain.ExperimentStatus.PROMOTING) {
            if ("STALE_DURING_PROMOTION".equals(error.code())) {
                experiment.markStale(error.getMessage());
            } else if ("MANUAL_RECOVERY_REQUIRED".equals(error.code())) {
                experiment.markRecoveryRequired(error.getMessage());
            } else {
                experiment.fail(error.code() + ": " + error.getMessage());
            }
            experiments.save(experiment);
            publishBestEffort(experimentId, "PROMOTION_BLOCKED", java.util.Map.of("status", error.code(), "detail", error.getMessage() == null ? "" : error.getMessage()));
        }
        return PromotionOutcome.blocked(error.code(), error.getMessage());
    }

    private void publishBestEffort(UUID experimentId, String type, java.util.Map<String, Object> payload) {
        try {
            events.publish(experimentId, type, payload);
        } catch (RuntimeException ignored) {
            // Lifecycle state and canonical contents remain authoritative when telemetry is unavailable.
        }
    }

    private void markJournalRecoveryRequired(com.pico.promotion.domain.PromotionJournal journal, String reason) {
        try {
            journals.markRecoveryRequired(journal, reason == null ? "Promotion failed" : reason, Instant.now());
        } catch (RuntimeException ignored) {
            // Leave APPLYING durable state intact so a later reconciliation can retry the fingerprint check.
        }
    }

    private void markJournalAborted(com.pico.promotion.domain.PromotionJournal journal, String reason) {
        try {
            journals.markAborted(journal, reason, Instant.now());
        } catch (RuntimeException ignored) {
            // A later recovery pass can close the still-open journal from canonical fingerprints.
        }
    }

    private PromotionOutcome classifyApplyFailure(Project project,
                                                  Snapshot base,
                                                  String preparedAgainst,
                                                  String candidateFingerprint,
                                                  UUID experimentId,
                                                  Experiment experiment,
                                                  com.pico.promotion.domain.PromotionJournal journal,
                                                  DomainException original) {
        String current;
        try {
            current = snapshotPort.currentFingerprint(project);
        } catch (RuntimeException fingerprintFailure) {
            markJournalRecoveryRequired(journal, "Unable to classify canonical after promotion failure: " + fingerprintFailure.getMessage());
            return recoveryOutcome(experimentId, "Unable to classify canonical after promotion failure");
        }
        if (candidateFingerprint.equals(current)) {
            try {
                states.commit(experiment, journal, current, Instant.now());
            } catch (RuntimeException lifecycleFailure) {
                markJournalRecoveryRequired(journal, "Canonical matches candidate but lifecycle commit needs reconciliation");
                return recoveryOutcome(experimentId,
                        "Canonical matches the candidate, but the experiment state could not be committed");
            }
            publishBestEffort(experimentId, "PROMOTED", java.util.Map.of("recovered", true, "fingerprint", current));
            return PromotionOutcome.promoted(java.util.List.of(), current);
        }
        if (base.fingerprint().equals(current) || preparedAgainst.equals(current)) {
            try {
                states.abortToBase(experiment, journal,
                        "Canonical remains at the promotion base; apply was rolled back", Instant.now());
            } catch (RuntimeException lifecycleFailure) {
                markJournalRecoveryRequired(journal, "Canonical is at base but lifecycle rollback needs reconciliation");
                return recoveryOutcome(experimentId,
                        "Canonical remains at the base, but the experiment state could not be reconciled");
            }
            publishBestEffort(experimentId, "PROMOTION_BLOCKED", java.util.Map.of("status", "PROMOTION_ABORTED", "fingerprint", current));
            return PromotionOutcome.blocked("PROMOTION_ABORTED", "Canonical remains unchanged at the promotion base");
        }
        markJournalRecoveryRequired(journal, original.getMessage());
        return recoveryOutcome(experimentId, "Canonical matches neither promotion base nor candidate");
    }

    private PromotionOutcome recoveryOutcome(UUID experimentId, String detail) {
        markExperimentRecoveryBestEffort(experimentId, detail);
        publishBestEffort(experimentId, "PROMOTION_RECOVERY_REQUIRED",
                java.util.Map.of("status", "RECOVERY_REQUIRED", "detail", detail));
        return PromotionOutcome.blocked("RECOVERY_REQUIRED", detail);
    }

    private void markExperimentRecoveryBestEffort(UUID experimentId, String reason) {
        try {
            Experiment current = experiments.findById(experimentId).orElse(null);
            if (current == null || current.status() == ExperimentStatus.RECOVERY_REQUIRED) return;
            if (current.status() == ExperimentStatus.VERIFIED
                    || current.status() == ExperimentStatus.PREPARING_PROMOTION
                    || current.status() == ExperimentStatus.PROMOTING
                    || current.status() == ExperimentStatus.PROMOTED) {
                current.requirePromotionRecovery(reason);
                experiments.save(current);
            }
        } catch (RuntimeException ignored) {
            // The recovery outcome remains visible even when a transient repository
            // failure prevents the lifecycle marker from being persisted immediately.
        }
    }

    public record PromotionOutcome(boolean promoted, String status, String detail, java.util.List<String> changedFiles, String fingerprint) {
        public static PromotionOutcome promoted(java.util.List<String> files, String fingerprint) {
            return new PromotionOutcome(true, "PROMOTED", "Canonical updated", files, fingerprint);
        }

        public static PromotionOutcome blocked(String status, String detail) {
            return new PromotionOutcome(false, status, detail, java.util.List.of(), null);
        }
    }
}
