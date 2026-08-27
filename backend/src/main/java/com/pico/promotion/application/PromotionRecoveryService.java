package com.pico.promotion.application;

import com.pico.experiment.domain.Experiment;
import com.pico.experiment.domain.ExperimentStatus;
import com.pico.port.EventSink;
import com.pico.port.ExperimentRepository;
import com.pico.port.ProjectRepository;
import com.pico.port.PromotionJournalPort;
import com.pico.port.PromotionLockPort;
import com.pico.port.SnapshotPort;
import com.pico.project.domain.Project;
import com.pico.promotion.domain.PromotionJournal;
import com.pico.promotion.domain.PromotionPhase;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

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
    }

    @Scheduled(fixedDelayString = "${pico.promotion.recovery-interval-ms:30000}")
    public void reconcileExpiredJournals() {
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

    private void reconcileExpired(PromotionJournal journal, Instant now) {
        try {
            promotionLock.withProjectLock(journal.projectId(), () -> {
                var claimed = journals.tryClaimExpired(journal, ownerId, now,
                        now.plus(java.time.Duration.ofMinutes(30)));
                claimed.ifPresent(claimedJournal -> reconcileOne(claimedJournal, now));
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
            journals.markRecoveryRequired(journal, "Experiment for promotion journal no longer exists", now);
            return;
        }
        Project project = projects.findById(journal.projectId()).orElse(null);
        if (project == null) {
            journals.markRecoveryRequired(journal, "Project for promotion journal no longer exists", now);
            return;
        }
        try {
            String current = snapshots.currentFingerprint(project);
            if (journal.phase() == PromotionPhase.APPLYING && journal.candidateFingerprint().equals(current)) {
                states.commit(experiment, journal, current, now);
                publish("PROMOTION_RECOVERED", journal, Map.of("status", "PROMOTED", "fingerprint", current));
                return;
            }
            if (journal.baseFingerprint().equals(current)) {
                if (experiment.status() == ExperimentStatus.PROMOTED) {
                    states.requireRecovery(experiment, journal,
                            "Canonical is at the promotion base but experiment is already PROMOTED", now);
                    publish("PROMOTION_RECOVERY_REQUIRED", journal,
                            Map.of("status", "RECOVERY_REQUIRED", "fingerprint", current));
                    return;
                }
                states.abortToBase(experiment, journal,
                        "Canonical still matches the promotion base; no apply was observed", now);
                publish("PROMOTION_RECOVERED", journal, Map.of("status", "VERIFIED", "fingerprint", current));
                return;
            }
            if (journal.phase() == PromotionPhase.PREPARED) {
                if (experiment.status() == ExperimentStatus.PREPARING_PROMOTION
                        || experiment.status() == ExperimentStatus.VERIFIED) {
                    states.stalePrepared(experiment, journal,
                            "Canonical changed while promotion preparation was interrupted", now);
                    publish("PROMOTION_RECOVERY_REQUIRED", journal,
                            Map.of("status", "STALE", "fingerprint", current));
                } else if (experiment.status() == ExperimentStatus.STALE) {
                    journals.markAborted(journal, "Canonical changed before promotion apply began", now);
                    publish("PROMOTION_RECOVERY_REQUIRED", journal,
                            Map.of("status", "STALE", "fingerprint", current));
                } else {
                    states.requireRecovery(experiment, journal,
                            "Promotion journal was prepared with an incompatible experiment state", now);
                    publish("PROMOTION_RECOVERY_REQUIRED", journal, Map.of("status", "RECOVERY_REQUIRED", "fingerprint", current));
                }
                return;
            }
            states.requireRecovery(experiment, journal,
                    "Canonical matches neither promotion base nor candidate", now);
            publish("PROMOTION_RECOVERY_REQUIRED", journal, Map.of("status", "RECOVERY_REQUIRED", "fingerprint", current));
        } catch (com.pico.shared.domain.DomainException error) {
            if ("PROMOTION_STATE_MISMATCH".equals(error.code())) {
                try {
                    journals.markRecoveryRequired(journal, error.getMessage(), now);
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

    private void publish(String type, PromotionJournal journal, Map<String, Object> payload) {
        try {
            events.publish(journal.experimentId(), type, payload);
        } catch (RuntimeException ignored) {
            // Recovery state is authoritative; event persistence is telemetry.
        }
    }
}
