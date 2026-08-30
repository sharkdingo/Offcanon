package com.offcanon.promotion.application;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.port.EventSink;
import com.offcanon.port.ExperimentRepository;
import com.offcanon.port.ProjectRepository;
import com.offcanon.port.PromotionJournalPort;
import com.offcanon.port.PromotionLockPort;
import com.offcanon.port.SnapshotPort;
import com.offcanon.project.domain.Project;
import com.offcanon.promotion.domain.PromotionJournal;
import com.offcanon.promotion.domain.PromotionPhase;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.shared.web.NotFoundException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

/**
 * Audits every open journal at startup, but reconciles only expired leases.
 * Active work is reported and left untouched; ambiguous canonical state is
 * surfaced for manual recovery instead of replayed.
 */
@Component
public class PromotionRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(PromotionRecoveryService.class);
    private final PromotionJournalPort journals;
    private final ExperimentRepository experiments;
    private final ProjectRepository projects;
    private final SnapshotPort snapshots;
    private final EventSink events;
    private final PromotionLockPort promotionLock;
    private final PromotionStateCoordinator states;
    private final String ownerId = "recovery-" + java.util.UUID.randomUUID();
    private final AtomicBoolean applicationReady = new AtomicBoolean();

    @Autowired
    public PromotionRecoveryService(PromotionJournalPort journals,
                                    ExperimentRepository experiments,
                                    ProjectRepository projects,
                                    SnapshotPort snapshots,
                                    EventSink events,
                                    PromotionLockPort promotionLock,
                                    PromotionStateCoordinator states) {
        this.journals = journals;
        this.experiments = experiments;
        this.projects = projects;
        this.snapshots = snapshots;
        this.events = events;
        this.promotionLock = promotionLock;
        this.states = states;
    }

    public PromotionRecoveryService(PromotionJournalPort journals,
                                    ExperimentRepository experiments,
                                    ProjectRepository projects,
                                    SnapshotPort snapshots,
                                    EventSink events,
                                    PromotionLockPort promotionLock) {
        this(journals, experiments, projects, snapshots, events, promotionLock,
                new PromotionStateCoordinator(experiments, journals, (org.springframework.transaction.PlatformTransactionManager) null));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        auditOpenJournals(Instant.now());
        applicationReady.set(true);
    }

    @Scheduled(fixedDelayString = "${offcanon.promotion.recovery-interval-ms:30000}")
    public void reconcileExpiredJournals() {
        if (!applicationReady.get()) return;
        reconcile(Instant.now());
    }

    public void reconcile(Instant now) {
        for (PromotionJournal journal : journals.findExpiredOpen(now)) {
            reconcileExpired(journal, now);
        }
    }

    public void auditOpenJournals(Instant now) {
        for (PromotionJournal journal : journals.findOpen()) {
            if (!journal.leaseUntil().isAfter(now)) {
                reconcileExpired(journal, now);
                continue;
            }
            log.warn("Startup detected open promotion {} in {} with an active lease owned by {} until {}",
                    journal.promotionId(), journal.phase(), journal.ownerId(), journal.leaseUntil());
            publish("PROMOTION_RECOVERY_DEFERRED", journal, Map.of(
                    "status", "ACTIVE_LEASE",
                    "phase", journal.phase().name(),
                    "ownerId", journal.ownerId(),
                    "leaseUntil", journal.leaseUntil().toString(),
                    "promotionBlocked", true));
        }
    }

    /**
     * Project-level recovery is the authoritative view used by clients. An
     * unresolved journal blocks the whole project, even when the associated
     * Experiment row has not yet caught up with the journal transition.
     */
    public ProjectRecoveryStatus status(UUID projectId) {
        if (projectId == null) throw new IllegalArgumentException("projectId must not be null");
        List<PromotionJournal> unresolved = journals.findUnresolvedByProject(projectId);
        if (!unresolved.isEmpty()) {
            PromotionJournal first = unresolved.getFirst();
            return new ProjectRecoveryStatus(projectId, true, first.promotionId(), first.experimentId(),
                    first.phase().name(), first.failureReason(), first.leaseUntil(), unresolved.size());
        }
        // A terminal journal can outlive the lifecycle marker when the two
        // stores are not transactionally coupled (for example MySQL + Redis).
        // Surface that mismatch as project recovery as well, so it cannot hide
        // behind an Experiment-only RECOVERY_REQUIRED status.
        try {
            for (Experiment experiment : experiments.findByProjectId(projectId)) {
                if (experiment.status() != ExperimentStatus.RECOVERY_REQUIRED) continue;
                PromotionJournal terminal = journals.findByExperimentId(experiment.id()).stream()
                        .filter(item -> item.phase() == PromotionPhase.COMMITTED
                                || item.phase() == PromotionPhase.ABORTED)
                        .reduce((left, right) -> right)
                        .orElse(null);
                if (terminal != null) {
                    return new ProjectRecoveryStatus(projectId, true, terminal.promotionId(),
                            experiment.id(), terminal.phase().name(), terminal.failureReason(),
                            terminal.leaseUntil(), 0);
                }
                return new ProjectRecoveryStatus(projectId, true, null, experiment.id(),
                        null, experiment.failureReason(), null, 0);
            }
        } catch (RuntimeException error) {
            // Keep the journal query authoritative when the experiment store is
            // temporarily unavailable; callers will retry the status request.
            throw error;
        }
        return ProjectRecoveryStatus.none(projectId);
    }

    /** Reconcile the oldest unresolved journal for a project. */
    public ManualReconciliation reconcileProject(UUID projectId) {
        if (projectId == null) throw new IllegalArgumentException("projectId must not be null");
        List<PromotionJournal> unresolved = journals.findUnresolvedByProject(projectId);
        if (!unresolved.isEmpty()) return reconcileRequired(unresolved.getFirst().experimentId());
        for (Experiment experiment : experiments.findByProjectId(projectId)) {
            if (experiment.status() == ExperimentStatus.RECOVERY_REQUIRED) {
                return reconcileRequired(experiment.id());
            }
        }
        throw new DomainException("PROMOTION_RECOVERY_JOURNAL_MISSING",
                "No promotion recovery is pending for project " + projectId);
    }

    /**
     * Resolves a journal already marked for manual recovery. This never replays
     * filesystem operations: it only records an outcome proven by the current
     * canonical fingerprint while holding the same project promotion lock.
     */
    public ManualReconciliation reconcileRequired(UUID experimentId) {
        Experiment initial = experiments.findById(experimentId)
                .orElseThrow(() -> new NotFoundException("Experiment not found: " + experimentId));
        Project initialProject = projects.findById(initial.projectId())
                .orElseThrow(() -> new NotFoundException("Project not found: " + initial.projectId()));
        return promotionLock.withProjectLock(initialProject.id(), () -> {
            assertPromotionLockHeld(initialProject.id());
            Experiment experiment = experiments.findById(experimentId)
                    .orElseThrow(() -> new NotFoundException("Experiment not found: " + experimentId));
            Project project = projects.findById(experiment.projectId())
                    .orElseThrow(() -> new NotFoundException("Project not found: " + experiment.projectId()));
            assertPromotionLockHeld(project.id());
            List<PromotionJournal> unresolved = journals.findUnresolvedByProject(project.id());
            List<PromotionJournal> matching = unresolved.stream()
                    .filter(journal -> journal.experimentId().equals(experimentId))
                    .toList();
            if (matching.isEmpty()) {
                // Repair a lifecycle marker that lagged behind a terminal
                // journal. This path is intentionally fingerprint-guarded and
                // runs under the project lock just like unresolved recovery.
                PromotionJournal terminal = journals.findByExperimentId(experimentId).stream()
                        .filter(item -> item.phase() == PromotionPhase.COMMITTED
                                || item.phase() == PromotionPhase.ABORTED)
                        .reduce((left, right) -> right)
                        .orElse(null);
                if (terminal != null && experiment.status() == ExperimentStatus.RECOVERY_REQUIRED) {
                    String current = snapshots.currentFingerprint(project);
                    assertPromotionLockHeld(project.id());
                    if (terminal.phase() == PromotionPhase.COMMITTED
                            && terminal.candidateFingerprint().equals(current)) {
                        states.reconcileCommitted(experiment, terminal, current, Instant.now());
                        return new ManualReconciliation(terminal.promotionId(), "PROMOTED",
                                "COMMITTED", current, "Lifecycle marker repaired from committed journal");
                    }
                    if (terminal.phase() == PromotionPhase.ABORTED
                            && terminal.baseFingerprint().equals(current)) {
                        states.reconcileAborted(experiment, terminal,
                                "Lifecycle marker repaired from aborted journal", Instant.now());
                        return new ManualReconciliation(terminal.promotionId(), "VERIFIED",
                                "ABORTED", current, "Lifecycle marker repaired from aborted journal");
                    }
                }
                throw new DomainException("PROMOTION_RECOVERY_JOURNAL_MISSING",
                        "No unresolved promotion journal exists for experiment " + experimentId);
            }
            if (matching.size() != 1) {
                throw new DomainException("PROMOTION_RECOVERY_JOURNAL_AMBIGUOUS",
                        "Multiple recovery journals exist for experiment " + experimentId);
            }
            PromotionJournal journal = matching.get(0);
            if (unresolved.isEmpty() || !unresolved.get(0).promotionId().equals(journal.promotionId())) {
                throw new DomainException("PROMOTION_RECOVERY_ORDER_CONFLICT",
                        "An earlier promotion journal must be reconciled first");
            }

            // A process can die after persisting PREPARED/APPLYING but before
            // writing RECOVERY_REQUIRED. The project-level journal is still
            // recoverable once its worker lease has expired; claim it before
            // inspecting canonical state so an active worker is never raced.
            Instant now = Instant.now();
            if (journal.phase() == PromotionPhase.PREPARED
                    || journal.phase() == PromotionPhase.APPLYING) {
                if (journal.leaseUntil().isAfter(now)) {
                    throw new DomainException("PROMOTION_RECOVERY_ACTIVE",
                            "Promotion " + journal.promotionId() + " is still owned until "
                                    + journal.leaseUntil());
                }
                journal = journals.tryClaimExpired(journal, ownerId, now,
                                now.plus(java.time.Duration.ofMinutes(30)))
                        .orElseThrow(() -> new DomainException("PROMOTION_RECOVERY_CONFLICT",
                                "Promotion journal changed while recovery was starting"));
            }

            String current = snapshots.currentFingerprint(project);
            assertPromotionLockHeld(project.id());
            if (journal.candidateFingerprint().equals(current)) {
                if (journal.phase() == PromotionPhase.PREPARED) {
                    // PREPARED proves that canonical apply had not started;
                    // matching the candidate is therefore an external change,
                    // not evidence that this promotion committed.
                    if (experiment.status() == ExperimentStatus.VERIFIED
                            || experiment.status() == ExperimentStatus.PREPARING_PROMOTION) {
                        states.stalePrepared(experiment,
                                journal,
                                "Canonical changed before promotion apply began",
                                now);
                        return new ManualReconciliation(journal.promotionId(), "STALE", "ABORTED", current,
                                "Canonical matched the candidate before promotion apply began");
                    }
                    throw new DomainException("PROMOTION_STATE_MISMATCH",
                            "Cannot reconcile PREPARED promotion from " + experiment.status());
                }
                assertPromotionLockHeld(project.id());
                if (journal.phase() == PromotionPhase.RECOVERY_REQUIRED) {
                    states.reconcileCommitted(experiment, journal, current, now);
                } else {
                    states.commit(experiment, journal, current, now);
                }
                assertPromotionLockHeld(project.id());
                publish("PROMOTION_MANUALLY_RECONCILED", journal,
                        Map.of("status", "PROMOTED", "fingerprint", current));
                return new ManualReconciliation(journal.promotionId(), "PROMOTED", "COMMITTED", current,
                        "Canonical matches the promotion candidate");
            }
            if (journal.baseFingerprint().equals(current)) {
                String reason = "Canonical matches the promotion base; manual recovery confirmed no applied result";
                assertPromotionLockHeld(project.id());
                if (journal.phase() == PromotionPhase.RECOVERY_REQUIRED) {
                    states.reconcileAborted(experiment, journal, reason, now);
                } else {
                    states.abortToBase(experiment, journal, reason, now);
                }
                assertPromotionLockHeld(project.id());
                publish("PROMOTION_MANUALLY_RECONCILED", journal,
                        Map.of("status", "VERIFIED", "fingerprint", current));
                return new ManualReconciliation(journal.promotionId(), "VERIFIED", "ABORTED", current, reason);
            }
            throw new DomainException("PROMOTION_RECOVERY_FINGERPRINT_MISMATCH",
                    "Canonical fingerprint " + current + " matches neither recovery base nor candidate; no state was changed");
        });
    }

    private void reconcileExpired(PromotionJournal journal, Instant now) {
        try {
            promotionLock.withProjectLock(journal.projectId(), () -> {
                assertPromotionLockHeld(journal.projectId());
                var claimed = journals.tryClaimExpired(journal, ownerId, now,
                        now.plus(java.time.Duration.ofMinutes(30)));
                claimed.ifPresent(claimedJournal -> {
                    assertPromotionLockHeld(claimedJournal.projectId());
                    reconcileOne(claimedJournal, now);
                });
                return null;
            });
        } catch (RuntimeException error) {
            // A transient lock or persistence failure must not prevent other projects'
            // journals from being reconciled in this pass.
            log.warn("Promotion journal reconciliation deferred for {}: {}", journal.promotionId(), error.getMessage());
        }
    }

    private void reconcileOne(PromotionJournal journal, Instant now) {
        Experiment experiment = experiments.findById(journal.experimentId()).orElse(null);
        if (experiment == null) {
            assertPromotionLockHeld(journal.projectId());
            journals.markRecoveryRequired(journal, "Experiment for promotion journal no longer exists", now);
            return;
        }
        Project project = projects.findById(journal.projectId()).orElse(null);
        if (project == null) {
            assertPromotionLockHeld(journal.projectId());
            journals.markRecoveryRequired(journal, "Project for promotion journal no longer exists", now);
            return;
        }
        try {
            assertPromotionLockHeld(project.id());
            String current = snapshots.currentFingerprint(project);
            // A fingerprint is only useful when it was read while this
            // recovery worker still owns the project lock. Do not classify a
            // journal from a read that raced with lock expiry.
            assertPromotionLockHeld(project.id());
            if (journal.phase() == PromotionPhase.APPLYING && journal.candidateFingerprint().equals(current)) {
                assertPromotionLockHeld(project.id());
                states.commit(experiment, journal, current, now);
                assertPromotionLockHeld(project.id());
                publish("PROMOTION_RECOVERED", journal, Map.of("status", "PROMOTED", "fingerprint", current));
                return;
            }
            if (journal.baseFingerprint().equals(current)) {
                if (experiment.status() == ExperimentStatus.PROMOTED) {
                    assertPromotionLockHeld(project.id());
                    states.requireRecovery(experiment, journal,
                            "Canonical is at the promotion base but experiment is already PROMOTED", now);
                    assertPromotionLockHeld(project.id());
                    publish("PROMOTION_RECOVERY_REQUIRED", journal,
                            Map.of("status", "RECOVERY_REQUIRED", "fingerprint", current));
                    return;
                }
                assertPromotionLockHeld(project.id());
                states.abortToBase(experiment, journal,
                        "Canonical still matches the promotion base; no apply was observed", now);
                assertPromotionLockHeld(project.id());
                publish("PROMOTION_RECOVERED", journal, Map.of("status", "VERIFIED", "fingerprint", current));
                return;
            }
            if (journal.phase() == PromotionPhase.PREPARED) {
                if (experiment.status() == ExperimentStatus.PREPARING_PROMOTION
                        || experiment.status() == ExperimentStatus.VERIFIED) {
                    assertPromotionLockHeld(project.id());
                    states.stalePrepared(experiment, journal,
                            "Canonical changed while promotion preparation was interrupted", now);
                    assertPromotionLockHeld(project.id());
                    publish("PROMOTION_RECOVERY_REQUIRED", journal,
                            Map.of("status", "STALE", "fingerprint", current));
                } else if (experiment.status() == ExperimentStatus.STALE) {
                    assertPromotionLockHeld(project.id());
                    journals.markAborted(journal, "Canonical changed before promotion apply began", now);
                    assertPromotionLockHeld(project.id());
                    publish("PROMOTION_RECOVERY_REQUIRED", journal,
                            Map.of("status", "STALE", "fingerprint", current));
                } else {
                    assertPromotionLockHeld(project.id());
                    states.requireRecovery(experiment, journal,
                            "Promotion journal was prepared with an incompatible experiment state", now);
                    assertPromotionLockHeld(project.id());
                    publish("PROMOTION_RECOVERY_REQUIRED", journal, Map.of("status", "RECOVERY_REQUIRED", "fingerprint", current));
                }
                return;
            }
            assertPromotionLockHeld(project.id());
            states.requireRecovery(experiment, journal,
                    "Canonical matches neither promotion base nor candidate", now);
            assertPromotionLockHeld(project.id());
            publish("PROMOTION_RECOVERY_REQUIRED", journal, Map.of("status", "RECOVERY_REQUIRED", "fingerprint", current));
        } catch (com.offcanon.shared.domain.DomainException error) {
            if ("PROMOTION_STATE_MISMATCH".equals(error.code())) {
                try {
                    assertPromotionLockHeld(journal.projectId());
                    journals.markRecoveryRequired(journal, error.getMessage(), now);
                    assertPromotionLockHeld(journal.projectId());
                    publish("PROMOTION_RECOVERY_REQUIRED", journal,
                            Map.of("status", "RECOVERY_REQUIRED", "detail", error.getMessage()));
                } catch (RuntimeException transitionFailure) {
                    log.warn("Unable to close mismatched promotion journal {}: {}", journal.promotionId(), transitionFailure.getMessage());
                }
            }
            log.warn("Promotion journal {} remains unresolved: {}", journal.promotionId(), error.getMessage());
        } catch (RuntimeException error) {
            // A fingerprint read can fail transiently. Keep the claimed journal open so a later
            // scheduled pass can retry instead of guessing a lifecycle outcome.
            log.warn("Promotion journal {} remains open after reconciliation error: {}", journal.promotionId(), error.getMessage());
        }
    }

    private void assertPromotionLockHeld(UUID projectId) {
        if (promotionLock != null) promotionLock.assertHeld(projectId);
    }

    private void publish(String type, PromotionJournal journal, Map<String, Object> payload) {
        try {
            events.publish(journal.experimentId(), type, payload);
        } catch (RuntimeException ignored) {
            // Recovery state is authoritative; event persistence is telemetry.
        }
    }

    public record ManualReconciliation(UUID promotionId,
                                       String experimentStatus,
                                       String journalPhase,
                                       String fingerprint,
                                       String detail) {
    }

    public record ProjectRecoveryStatus(UUID projectId,
                                        boolean recoveryRequired,
                                        UUID promotionId,
                                        UUID experimentId,
                                        String journalPhase,
                                        String failureReason,
                                        Instant leaseUntil,
                                        int unresolvedCount) {
        public static ProjectRecoveryStatus none(UUID projectId) {
            return new ProjectRecoveryStatus(projectId, false, null, null, null, null, null, 0);
        }
    }
}
