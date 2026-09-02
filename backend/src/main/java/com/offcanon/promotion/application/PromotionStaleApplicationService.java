package com.offcanon.promotion.application;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.port.EventSink;
import com.offcanon.port.ExperimentRepository;
import com.offcanon.port.ProjectRepository;
import com.offcanon.port.PromotionLockPort;
import com.offcanon.port.PromotionJournalPort;
import com.offcanon.port.SnapshotPort;
import com.offcanon.port.SnapshotRepository;
import com.offcanon.port.SessionRunLeasePort;
import com.offcanon.project.domain.Project;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.shared.web.NotFoundException;
import com.offcanon.workspace.domain.Snapshot;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class PromotionStaleApplicationService {
    private final ExperimentRepository experiments;
    private final ProjectRepository projects;
    private final SnapshotRepository snapshots;
    private final SnapshotPort snapshotPort;
    private final EventSink events;
    private final PromotionLockPort promotionLock;
    private final PromotionJournalPort promotionJournals;
    private final SessionRunLeasePort sessionRunLease;

    @Autowired
    public PromotionStaleApplicationService(ExperimentRepository experiments,
                                             ProjectRepository projects,
                                             SnapshotRepository snapshots,
                                             SnapshotPort snapshotPort,
                                             EventSink events,
                                             PromotionLockPort promotionLock,
                                             PromotionJournalPort promotionJournals,
                                             SessionRunLeasePort sessionRunLease) {
        this.experiments = experiments;
        this.projects = projects;
        this.snapshots = snapshots;
        this.snapshotPort = snapshotPort;
        this.events = events;
        this.promotionLock = Objects.requireNonNull(promotionLock, "promotionLock");
        this.promotionJournals = promotionJournals;
        this.sessionRunLease = sessionRunLease;
    }

    /** Compatibility constructor for focused tests that do not model session leases. */
    public PromotionStaleApplicationService(ExperimentRepository experiments,
                                             ProjectRepository projects,
                                             SnapshotRepository snapshots,
                                             SnapshotPort snapshotPort,
                                             EventSink events,
                                             PromotionLockPort promotionLock,
                                             PromotionJournalPort promotionJournals) {
        this(experiments, projects, snapshots, snapshotPort, events, promotionLock,
                promotionJournals, null);
    }

    public StaleConfirmation confirm(UUID experimentId) {
        Experiment initial = experiments.findById(experimentId)
                .orElseThrow(() -> new NotFoundException("Experiment not found: " + experimentId));
        Project initialProject = projects.findById(initial.projectId())
                .orElseThrow(() -> new NotFoundException("Project not found: " + initial.projectId()));
        return promotionLock.withProjectLock(initialProject.id(), () -> {
            promotionLock.assertHeld(initialProject.id());
            Experiment experiment = experiments.findById(experimentId)
                    .orElseThrow(() -> new NotFoundException("Experiment not found: " + experimentId));
            Project project = projects.findById(experiment.projectId())
                    .orElseThrow(() -> new NotFoundException("Project not found: " + experiment.projectId()));
            if (!project.id().equals(initialProject.id())) {
                throw new DomainException("PROJECT_CHANGED", "Experiment project changed while confirmation was starting");
            }
            var unresolved = promotionJournals.findUnresolvedByProject(project.id());
            if (!unresolved.isEmpty()) {
                var blocking = unresolved.getFirst();
                throw new DomainException("PROMOTION_RECOVERY_PENDING",
                        "Promotion " + blocking.promotionId() + " is still "
                                + blocking.phase().name() + "; reconcile it before confirming stale state");
            }
            if (experiment.status() != ExperimentStatus.VERIFIED) {
                return StaleConfirmation.unchanged("NOT_VERIFIED",
                        "Only a verified experiment can be marked stale", null);
            }
            if (experiment.baseSnapshotId() == null) {
                return StaleConfirmation.unchanged("BASE_SNAPSHOT_MISSING",
                        "Verified experiment has no base snapshot", null);
            }

            if (experiments.hasRunningExperiment(experiment.sessionId(), experiment.id())) {
                publishSessionBlocked(experimentId);
                return StaleConfirmation.unchanged("SESSION_ALREADY_RUNNING",
                        "A later experiment in this session is still active", null);
            }
            SessionRunLeasePort.Lease sessionLease = acquireSessionLease(experiment);
            if (sessionLease == null) {
                publishSessionBlocked(experimentId);
                return StaleConfirmation.unchanged("SESSION_ALREADY_RUNNING",
                        "A later experiment in this session is still active", null);
            }
            try {
                sessionLease.assertHeld();
                if (experiments.hasRunningExperiment(experiment.sessionId(), experiment.id())) {
                    publishSessionBlocked(experimentId);
                    return StaleConfirmation.unchanged("SESSION_ALREADY_RUNNING",
                            "A later experiment in this session is still active", null);
                }

                Snapshot base = snapshots.findById(experiment.baseSnapshotId())
                        .orElseThrow(() -> new NotFoundException("Snapshot not found: " + experiment.baseSnapshotId()));
                promotionLock.assertHeld(project.id());
                String currentFingerprint = snapshotPort.currentFingerprint(project);
                promotionLock.assertHeld(project.id());
                if (base.fingerprint().equals(currentFingerprint)) {
                    return StaleConfirmation.unchanged("CANONICAL_MATCHES_BASE",
                            "Canonical matches the experiment base; experiment remains verified", currentFingerprint);
                }

                try {
                    promotionLock.assertHeld(project.id());
                    sessionLease.assertHeld();
                    experiment.markStale("Canonical changed after this experiment started");
                    promotionLock.assertHeld(project.id());
                    sessionLease.assertHeld();
                    experiments.save(experiment);
                } catch (DomainException error) {
                    if (!"EXPERIMENT_VERSION_CONFLICT".equals(error.code())) throw error;
                    Experiment current = experiments.findById(experimentId).orElse(experiment);
                    return StaleConfirmation.unchanged("CONCURRENT_STATE_CHANGE",
                            "Experiment is now " + current.status().name(), currentFingerprint);
                }
                publishBestEffort(experimentId, currentFingerprint);
                return StaleConfirmation.marked(currentFingerprint);
            } finally {
                releaseSessionLeaseBestEffort(sessionLease);
            }
        });
    }

    private void publishBestEffort(UUID experimentId, String currentFingerprint) {
        try {
            events.publish(experimentId, "PROMOTION_BLOCKED",
                    Map.of("status", "STALE", "currentFingerprint", currentFingerprint));
        } catch (RuntimeException ignored) {
            // Experiment state remains authoritative when telemetry is unavailable.
        }
    }

    private SessionRunLeasePort.Lease acquireSessionLease(Experiment experiment) {
        if (sessionRunLease == null) return NOOP_SESSION_LEASE;
        return sessionRunLease.tryAcquire(experiment.sessionId(), experiment.id()).orElse(null);
    }

    private void releaseSessionLeaseBestEffort(SessionRunLeasePort.Lease lease) {
        if (lease == null || lease == NOOP_SESSION_LEASE) return;
        try {
            lease.release();
        } catch (RuntimeException ignored) {
            // A remote lease will expire; preserve the stale-confirmation result.
        }
    }

    private void publishSessionBlocked(UUID experimentId) {
        try {
            events.publish(experimentId, "PROMOTION_BLOCKED", Map.of(
                    "status", "SESSION_ALREADY_RUNNING",
                    "detail", "A later experiment in this session is still active"));
        } catch (RuntimeException ignored) {
            // Lifecycle state remains authoritative when telemetry is unavailable.
        }
    }

    private static final SessionRunLeasePort.Lease NOOP_SESSION_LEASE = new SessionRunLeasePort.Lease() {
        private static final UUID ZERO = new UUID(0L, 0L);

        @Override public UUID sessionId() { return ZERO; }
        @Override public UUID experimentId() { return ZERO; }
        @Override public void assertHeld() { }
        @Override public void release() { }
    };

    public record StaleConfirmation(boolean markedStale,
                                    String status,
                                    String detail,
                                    String currentFingerprint) {
        public static StaleConfirmation marked(String currentFingerprint) {
            return new StaleConfirmation(true, "STALE",
                    "Experiment marked stale; canonical was not modified", currentFingerprint);
        }

        public static StaleConfirmation unchanged(String status, String detail, String currentFingerprint) {
            return new StaleConfirmation(false, status, detail, currentFingerprint);
        }
    }
}
