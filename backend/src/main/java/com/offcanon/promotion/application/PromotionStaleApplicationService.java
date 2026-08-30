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
import com.offcanon.project.domain.Project;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.shared.web.NotFoundException;
import com.offcanon.workspace.domain.Snapshot;
import org.springframework.stereotype.Service;

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

    public PromotionStaleApplicationService(ExperimentRepository experiments,
                                             ProjectRepository projects,
                                             SnapshotRepository snapshots,
                                             SnapshotPort snapshotPort,
                                             EventSink events,
                                             PromotionLockPort promotionLock,
                                             PromotionJournalPort promotionJournals) {
        this.experiments = experiments;
        this.projects = projects;
        this.snapshots = snapshots;
        this.snapshotPort = snapshotPort;
        this.events = events;
        this.promotionLock = Objects.requireNonNull(promotionLock, "promotionLock");
        this.promotionJournals = promotionJournals;
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
                experiment.markStale("Canonical changed after this experiment started");
                promotionLock.assertHeld(project.id());
                experiments.save(experiment);
            } catch (DomainException error) {
                if (!"EXPERIMENT_VERSION_CONFLICT".equals(error.code())) throw error;
                Experiment current = experiments.findById(experimentId).orElse(experiment);
                return StaleConfirmation.unchanged("CONCURRENT_STATE_CHANGE",
                        "Experiment is now " + current.status().name(), currentFingerprint);
            }
            publishBestEffort(experimentId, currentFingerprint);
            return StaleConfirmation.marked(currentFingerprint);
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
