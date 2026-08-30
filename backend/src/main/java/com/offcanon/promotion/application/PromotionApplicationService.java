package com.offcanon.promotion.application;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.port.ExperimentRepository;
import com.offcanon.port.EventSink;
import com.offcanon.port.PromotionLockPort;
import com.offcanon.port.PromotionPort;
import com.offcanon.port.ProjectRepository;
import com.offcanon.port.SnapshotPort;
import com.offcanon.port.SnapshotRepository;
import com.offcanon.port.WorkspacePort;
import com.offcanon.port.VerificationPort;
import com.offcanon.port.PromotionJournalPort;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.project.domain.Project;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.shared.web.NotFoundException;
import com.offcanon.workspace.domain.Snapshot;
import com.offcanon.verification.domain.VerificationPurpose;
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
        if (experiment.status() == com.offcanon.experiment.domain.ExperimentStatus.PROMOTED) {
            return PromotionOutcome.blocked("ALREADY_PROMOTED", "Experiment has already been promoted");
        }
        if (experiment.status() != com.offcanon.experiment.domain.ExperimentStatus.VERIFIED) {
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
            String candidateFingerprint;
            PromotionPort.PromotionPlan promotionPlan;
            try {
                candidateFingerprint = snapshotPort.fingerprintWorkspace(project, candidate, base.fingerprint());
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
                    discardCandidateBestEffort(candidate);
                    publishBestEffort(experimentId, "PROMOTION_BLOCKED", java.util.Map.of(
                            "status", "PROMOTION_VERIFICATION_FAILED",
                            "detail", candidateVerification.failureReason()));
                    return PromotionOutcome.blocked("PROMOTION_VERIFICATION_FAILED", candidateVerification.failureReason());
                }
                promotionPlan = promotionPort.plan(project, base, experiment, candidate);
            } catch (RuntimeException preparationFailure) {
                discardCandidateBestEffort(candidate);
                throw preparationFailure;
            }

            com.offcanon.promotion.domain.PromotionJournal preparedJournal;
            try {
                preparedJournal = journals.create(com.offcanon.promotion.domain.PromotionJournal.create(
                        experiment.id(), project.id(), base.fingerprint(), candidateFingerprint, candidate,
                        promotionPlan.touchedFiles(), promotionPlan.preimageHashes(), promotionPlan.postimageHashes(), ownerId,
                        Instant.now(), Instant.now().plus(Duration.ofMinutes(30))));
            } catch (RuntimeException journalFailure) {
                // No durable journal owns this candidate.  Release it immediately;
                // retention is only a fallback for an adapter-level delete failure.
                discardCandidateBestEffort(candidate);
                throw journalFailure;
            }

            try {
                return promotionLock.withProjectLock(project.id(), () -> {
                promotionLock.assertHeld(project.id());
                // Re-read the lifecycle marker after acquiring the project
                // lock. A concurrent stale confirmation or promotion may have
                // advanced the detached object used during preparation; never
                // let that stale object start a second apply.
                Experiment lockedExperiment = experiments.findById(experimentId)
                        .orElseThrow(() -> new DomainException("EXPERIMENT_MISSING",
                                "Promotion experiment disappeared"));
                if (lockedExperiment.status() != ExperimentStatus.VERIFIED) {
                    markJournalAborted(preparedJournal,
                            "Experiment changed to " + lockedExperiment.status() + " before promotion began");
                    discardCandidateBestEffort(candidate);
                    return PromotionOutcome.blocked("CONCURRENT_STATE_CHANGE",
                            "Experiment is now " + lockedExperiment.status().name());
                }
                String current = snapshotPort.currentFingerprint(project);
                if (!preparedAgainst.equals(current)) {
                    states.stalePrepared(experiment, preparedJournal,
                            "Canonical changed while promotion was being prepared", Instant.now());
                    return PromotionOutcome.blocked("STALE_DURING_PROMOTION", current);
                }
                promotionLock.assertHeld(project.id());
                String lockedCandidateFingerprint = snapshotPort.fingerprintWorkspace(project, candidate, base.fingerprint());
                PromotionPort.PromotionPlan lockedPlan = promotionPort.plan(project, base, experiment, candidate);
                if (!candidateFingerprint.equals(lockedCandidateFingerprint) || !promotionPlan.equals(lockedPlan)) {
                    journals.markAborted(preparedJournal,
                            "Promotion candidate changed after verification and planning", Instant.now());
                    discardCandidateBestEffort(candidate);
                    publishBestEffort(experimentId, "PROMOTION_BLOCKED", java.util.Map.of(
                            "status", "PROMOTION_CANDIDATE_MUTATED"));
                    return PromotionOutcome.blocked("PROMOTION_CANDIDATE_MUTATED",
                            "Promotion candidate changed after verification and planning");
                }
                promotionLock.assertHeld(project.id());
                var unresolved = journals.findUnresolvedByProject(project.id());
                if (!unresolved.isEmpty() && !unresolved.get(0).promotionId().equals(preparedJournal.promotionId())) {
                    journals.markAborted(preparedJournal,
                            "An earlier promotion journal must be reconciled first", Instant.now());
                    discardCandidateBestEffort(candidate);
                    return PromotionOutcome.blocked("PROMOTION_RECOVERY_PENDING",
                            "Promotion " + unresolved.get(0).promotionId() + " is still "
                                     + unresolved.get(0).phase().name());
                }
                promotionLock.assertHeld(project.id());
                var applyingJournal = states.beginApplying(lockedExperiment, preparedJournal, Instant.now());
                publishBestEffort(experimentId, "PROMOTION_PREPARING", java.util.Map.of("status", experiment.status().name()));
                boolean canonicalUpdated = false;
                try {
                    promotionLock.assertHeld(project.id());
                    PromotionPort.PromotionResult result = promotionPort.apply(project, base, experiment, candidate, promotionPlan);
                    canonicalUpdated = result.applied();
                    if (!result.applied()) {
                        throw new DomainException("PROMOTION_APPLY_FAILED", "Promotion adapter did not apply the candidate");
                    }
                    promotionLock.assertHeld(project.id());
                    String finalFingerprint = snapshotPort.currentFingerprint(project);
                    if (!candidateFingerprint.equals(finalFingerprint)) {
                        throw new DomainException("MANUAL_RECOVERY_REQUIRED",
                                "Canonical changed during final apply; inspect it before another promotion");
                    }
                    promotionLock.assertHeld(project.id());
                    states.commit(experiment, applyingJournal, finalFingerprint, Instant.now());
                    // Verify ownership once more after the paired durable
                    // commit. If the lease expires in this tiny window, the
                    // terminal journal still wins and the caller must not
                    // downgrade the experiment to recovery.
                    promotionLock.assertHeld(project.id());
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
                if (error instanceof DomainException domain
                        && "PROMOTION_LOCK_LOST".equals(domain.code())) {
                    markJournalRecoveryRequired(preparedJournal,
                            "Promotion lock lease was lost before canonical state could be classified");
                    throw new DomainException("MANUAL_RECOVERY_REQUIRED",
                            "Promotion lock lease was lost; inspect canonical state before reconciling");
                }
                settleJournalAfterFailure(preparedJournal, "Promotion preparation did not reach canonical apply: "
                        + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
                throw error;
            }
        } catch (DomainException error) {
            synchronizeExperimentWithJournal(experimentId, error);
            return handleFailure(experimentId, experiments.findById(experimentId).orElse(experiment), error);
        } catch (RuntimeException error) {
            DomainException failure = new DomainException("PROMOTION_FAILED", error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage());
            synchronizeExperimentWithJournal(experimentId, failure);
            return handleFailure(experimentId, experiments.findById(experimentId).orElse(experiment), failure);
        }
    }

    private PromotionOutcome handleFailure(UUID experimentId, Experiment experiment, DomainException error) {
        alignTerminalJournal(experiment);
        if ("EXPERIMENT_VERSION_CONFLICT".equals(error.code())) {
            Experiment current = experiments.findById(experimentId).orElse(experiment);
            return PromotionOutcome.blocked("CONCURRENT_STATE_CHANGE",
                    "Experiment is now " + current.status().name());
        }
        if (experiment.status() == com.offcanon.experiment.domain.ExperimentStatus.PREPARING_PROMOTION) {
            if ("STALE_DURING_PROMOTION".equals(error.code())) {
                experiment.markStale(error.getMessage());
            } else if ("MANUAL_RECOVERY_REQUIRED".equals(error.code())
                    || "PROMOTION_LOCK_LOST".equals(error.code())) {
                experiment.requirePromotionRecovery(error.getMessage());
            } else {
                experiment.abortPromotion(error.code() + ": " + error.getMessage());
            }
            experiments.save(experiment);
            publishBestEffort(experimentId, "PROMOTION_BLOCKED", java.util.Map.of("status", error.code(), "detail", error.getMessage() == null ? "" : error.getMessage()));
        } else if (experiment.status() == com.offcanon.experiment.domain.ExperimentStatus.PROMOTING) {
            if ("STALE_DURING_PROMOTION".equals(error.code())) {
                experiment.markStale(error.getMessage());
            } else if ("MANUAL_RECOVERY_REQUIRED".equals(error.code())
                    || "PROMOTION_LOCK_LOST".equals(error.code())) {
                experiment.markRecoveryRequired(error.getMessage());
            } else {
                experiment.fail(error.code() + ": " + error.getMessage());
            }
            experiments.save(experiment);
            publishBestEffort(experimentId, "PROMOTION_BLOCKED", java.util.Map.of("status", error.code(), "detail", error.getMessage() == null ? "" : error.getMessage()));
        }
        return PromotionOutcome.blocked(error.code(), error.getMessage());
    }

    private void alignTerminalJournal(Experiment experiment) {
        if (experiment == null) return;
        try {
            var terminal = journals.findByExperimentId(experiment.id()).stream()
                    .filter(item -> item.phase() == com.offcanon.promotion.domain.PromotionPhase.COMMITTED
                            || item.phase() == com.offcanon.promotion.domain.PromotionPhase.ABORTED)
                    .reduce((left, right) -> right)
                    .orElse(null);
            if (terminal == null) return;
            if (terminal.phase() == com.offcanon.promotion.domain.PromotionPhase.COMMITTED) {
                states.commit(experiment, terminal,
                        terminal.resultingFingerprint() == null
                                ? terminal.candidateFingerprint() : terminal.resultingFingerprint(), Instant.now());
            } else {
                states.abortToBase(experiment, terminal,
                        terminal.failureReason() == null ? "Promotion was aborted" : terminal.failureReason(), Instant.now());
            }
        } catch (RuntimeException ignored) {
            // Preserve the original promotion outcome; the project-level
            // recovery endpoint can repair an incompatible lifecycle marker.
        }
    }

    private void publishBestEffort(UUID experimentId, String type, java.util.Map<String, Object> payload) {
        try {
            events.publish(experimentId, type, payload);
        } catch (RuntimeException ignored) {
            // Lifecycle state and canonical contents remain authoritative when telemetry is unavailable.
        }
    }

    private void markJournalRecoveryRequired(com.offcanon.promotion.domain.PromotionJournal journal, String reason) {
        try {
            var durable = journals.findById(journal.promotionId()).orElse(null);
            if (durable == null
                    || durable.phase() == com.offcanon.promotion.domain.PromotionPhase.COMMITTED
                    || durable.phase() == com.offcanon.promotion.domain.PromotionPhase.ABORTED
                    || durable.phase() == com.offcanon.promotion.domain.PromotionPhase.RECOVERY_REQUIRED) {
                return;
            }
            journals.markRecoveryRequired(durable, reason == null ? "Promotion failed" : reason, Instant.now());
        } catch (RuntimeException ignored) {
            // Leave APPLYING durable state intact so a later reconciliation can retry the fingerprint check.
        }
    }

    private void markJournalAborted(com.offcanon.promotion.domain.PromotionJournal journal, String reason) {
        try {
            var durable = journals.findById(journal.promotionId()).orElse(null);
            if (durable == null
                    || durable.phase() == com.offcanon.promotion.domain.PromotionPhase.COMMITTED
                    || durable.phase() == com.offcanon.promotion.domain.PromotionPhase.ABORTED
                    || durable.phase() == com.offcanon.promotion.domain.PromotionPhase.RECOVERY_REQUIRED) {
                return;
            }
            journals.markAborted(durable, reason, Instant.now());
        } catch (RuntimeException ignored) {
            // A later recovery pass can close the still-open journal from canonical fingerprints.
        }
    }

    /**
     * Resolve a failure that escaped the project-lock callback. PREPARED means
     * no canonical write was admitted; APPLYING is inherently ambiguous and
     * must remain visible to the recovery worker. Always reload the journal so
     * a partially completed paired write cannot be overwritten by a stale
     * object.
     */
    private void settleJournalAfterFailure(com.offcanon.promotion.domain.PromotionJournal journal,
                                           String reason) {
        try {
            var durable = journals.findById(journal.promotionId()).orElse(null);
            if (durable == null
                    || durable.phase() == com.offcanon.promotion.domain.PromotionPhase.COMMITTED
                    || durable.phase() == com.offcanon.promotion.domain.PromotionPhase.ABORTED
                    || durable.phase() == com.offcanon.promotion.domain.PromotionPhase.RECOVERY_REQUIRED) {
                return;
            }
            if (durable.phase() == com.offcanon.promotion.domain.PromotionPhase.APPLYING) {
                markJournalRecoveryRequired(durable, reason);
            } else {
                markJournalAborted(durable, reason);
            }
        } catch (RuntimeException ignored) {
            // The recovery scheduler will claim an unresolved journal once its
            // lease expires; never hide the original promotion failure.
        }
    }

    private void discardCandidateBestEffort(java.nio.file.Path candidate) {
        if (candidate == null) return;
        try {
            workspaces.discard(candidate);
        } catch (RuntimeException ignored) {
            // A candidate with no durable journal is unreferenced; retention is
            // the final cleanup boundary if immediate discard is unavailable.
        }
    }

    private PromotionOutcome classifyApplyFailure(Project project,
                                                  Snapshot base,
                                                  String preparedAgainst,
                                                  String candidateFingerprint,
                                                  UUID experimentId,
                                                  Experiment experiment,
                                                  com.offcanon.promotion.domain.PromotionJournal journal,
                                                  DomainException original) {
        // A lifecycle/journal commit can succeed just before a final lock
        // assertion or response cleanup fails. Read the durable phase first so
        // that a terminal journal is never downgraded to recovery merely due
        // to an observation failure after commit.
        try {
            var durable = journals.findById(journal.promotionId()).orElse(null);
            if (durable != null && durable.phase() == com.offcanon.promotion.domain.PromotionPhase.COMMITTED) {
                try {
                    states.commit(experiment, durable,
                            durable.resultingFingerprint() == null ? candidateFingerprint : durable.resultingFingerprint(),
                            Instant.now());
                } catch (RuntimeException ignored) {
                    // The journal remains authoritative; a later recovery pass
                    // can repair an incompatible or temporarily unavailable
                    // lifecycle row.
                }
                return PromotionOutcome.promoted(java.util.List.of(),
                        durable.resultingFingerprint() == null ? candidateFingerprint : durable.resultingFingerprint());
            }
            if (durable != null && durable.phase() == com.offcanon.promotion.domain.PromotionPhase.ABORTED) {
                try {
                    states.abortToBase(experiment, durable,
                            durable.failureReason() == null ? "Promotion was aborted" : durable.failureReason(),
                            Instant.now());
                } catch (RuntimeException ignored) {
                    // Keep the terminal journal outcome; lifecycle repair is
                    // retried by the project recovery path.
                }
                return PromotionOutcome.blocked("PROMOTION_ABORTED",
                        durable.failureReason() == null ? "Promotion was aborted" : durable.failureReason());
            }
        } catch (RuntimeException ignored) {
            // Continue with the conservative recovery path below when the
            // journal store itself is unavailable.
        }
        // A distributed lock loss invalidates every automatic conclusion. Even
        // if a fingerprint happens to match base or candidate, another writer
        // could have entered the critical section after the lease expired.
        if ("PROMOTION_LOCK_LOST".equals(original.code())) {
            markJournalRecoveryRequired(journal,
                    "Promotion lock lease was lost while canonical state was being changed");
            return recoveryOutcome(experimentId,
                    "Promotion lock lease was lost; inspect canonical state before reconciling");
        }
        String current;
        try {
            // A non-lock apply error can race lease renewal. Never
            // classify canonical state, or mutate the lifecycle journal, after
            // ownership has expired: the fingerprint could belong to another
            // writer that entered immediately after our lease ended.
            promotionLock.assertHeld(project.id());
            current = snapshotPort.currentFingerprint(project);
            promotionLock.assertHeld(project.id());
        } catch (RuntimeException fingerprintFailure) {
            markJournalRecoveryRequired(journal, "Unable to classify canonical after promotion failure: " + fingerprintFailure.getMessage());
            return recoveryOutcome(experimentId, "Unable to classify canonical after promotion failure");
        }
        if (candidateFingerprint.equals(current)) {
            try {
                promotionLock.assertHeld(project.id());
                states.commit(experiment, journal, current, Instant.now());
                promotionLock.assertHeld(project.id());
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
                promotionLock.assertHeld(project.id());
                states.abortToBase(experiment, journal,
                        "Canonical remains at the promotion base; apply was rolled back", Instant.now());
                promotionLock.assertHeld(project.id());
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
            // A terminal journal is authoritative. In particular, do not turn
            // a successfully committed experiment back into RECOVERY_REQUIRED
            // merely because a lock assertion failed during final cleanup.
            var unresolved = journals.findUnresolvedByProject(current.projectId()).stream()
                    .filter(journal -> journal.experimentId().equals(experimentId))
                    .findFirst();
            if (unresolved.isEmpty()) return;
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

    /**
     * Keep the lifecycle marker aligned with a journal after an exception has
     * crossed the lock boundary. This is deliberately best-effort: if the
     * repository is unavailable, the unresolved journal remains the project
     * level source of truth and the next recovery pass can repair the marker.
     */
    private void synchronizeExperimentWithJournal(UUID experimentId, DomainException error) {
        if (experimentId == null) return;
        try {
            Experiment current = experiments.findById(experimentId).orElse(null);
            if (current == null) return;
            var journalsForProject = journals.findUnresolvedByProject(current.projectId());
            var unresolved = journalsForProject.stream()
                    .filter(journal -> journal.experimentId().equals(experimentId))
                    .findFirst();
            if (unresolved.isEmpty()) return;
            var journal = unresolved.get();
            if (journal.phase() == com.offcanon.promotion.domain.PromotionPhase.RECOVERY_REQUIRED
                    || journal.phase() == com.offcanon.promotion.domain.PromotionPhase.APPLYING
                    || (journal.phase() == com.offcanon.promotion.domain.PromotionPhase.PREPARED
                    && ("MANUAL_RECOVERY_REQUIRED".equals(error.code())
                    || "PROMOTION_LOCK_LOST".equals(error.code())))) {
                markExperimentRecoveryBestEffort(experimentId,
                        error.getMessage() == null ? "Promotion requires reconciliation" : error.getMessage());
            }
        } catch (RuntimeException ignored) {
            // Preserve the original outcome. The project journal remains
            // queryable and can be reconciled independently of this marker.
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
