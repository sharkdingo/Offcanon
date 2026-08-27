package com.pico.promotion.application;

import com.pico.experiment.domain.Experiment;
import com.pico.port.ExperimentRepository;
import com.pico.port.PromotionLockPort;
import com.pico.port.PromotionPort;
import com.pico.port.ProjectRepository;
import com.pico.port.SnapshotPort;
import com.pico.port.SnapshotRepository;
import com.pico.port.WorkspacePort;
import com.pico.project.domain.Project;
import com.pico.shared.domain.DomainException;
import com.pico.shared.web.NotFoundException;
import com.pico.workspace.domain.Snapshot;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class PromotionApplicationService {
    private final ExperimentRepository experiments;
    private final ProjectRepository projects;
    private final SnapshotRepository snapshots;
    private final SnapshotPort snapshotPort;
    private final WorkspacePort workspaces;
    private final PromotionPort promotionPort;
    private final PromotionLockPort promotionLock;

    public PromotionApplicationService(ExperimentRepository experiments,
                                       ProjectRepository projects,
                                       SnapshotRepository snapshots,
                                       SnapshotPort snapshotPort,
                                       WorkspacePort workspaces,
                                       PromotionPort promotionPort,
                                       PromotionLockPort promotionLock) {
        this.experiments = experiments;
        this.projects = projects;
        this.snapshots = snapshots;
        this.snapshotPort = snapshotPort;
        this.workspaces = workspaces;
        this.promotionPort = promotionPort;
        this.promotionLock = promotionLock;
    }

    public PromotionOutcome promote(UUID experimentId) {
        Experiment experiment = experiments.findById(experimentId)
                .orElseThrow(() -> new NotFoundException("Experiment not found: " + experimentId));
        Project project = projects.findById(experiment.projectId())
                .orElseThrow(() -> new NotFoundException("Project not found: " + experiment.projectId()));
        Snapshot base = snapshots.findById(experiment.baseSnapshotId())
                .orElseThrow(() -> new NotFoundException("Snapshot not found: " + experiment.baseSnapshotId()));

        experiment.beginPromotion();
        experiments.save(experiment);
        String preparedAgainst = snapshotPort.currentFingerprint(project);
        if (!base.fingerprint().equals(preparedAgainst)) {
            experiment.markStale("Canonical changed after this experiment started");
            experiments.save(experiment);
            return PromotionOutcome.blocked("STALE", preparedAgainst);
        }
        var candidate = workspaces.createPromotionCandidate(base, experiment);

        return promotionLock.withProjectLock(project.id(), () -> {
            String current = snapshotPort.currentFingerprint(project);
            if (!preparedAgainst.equals(current)) {
                experiment.markStale("Canonical changed while promotion was being prepared");
                experiments.save(experiment);
                return PromotionOutcome.blocked("STALE_DURING_PROMOTION", current);
            }
            experiment.markPromoting();
            experiments.save(experiment);
            try {
                PromotionPort.PromotionResult result = promotionPort.apply(project, base, experiment, candidate);
                experiment.markPromoted();
                experiments.save(experiment);
                return PromotionOutcome.promoted(result.changedFiles(), result.resultingFingerprint());
            } catch (DomainException error) {
                if ("STALE_DURING_PROMOTION".equals(error.code())) {
                    experiment.markStale(error.getMessage());
                } else if ("MANUAL_RECOVERY_REQUIRED".equals(error.code())) {
                    experiment.markRecoveryRequired(error.getMessage());
                } else {
                    experiment.fail(error.code() + ": " + error.getMessage());
                }
                experiments.save(experiment);
                return PromotionOutcome.blocked(error.code(), error.getMessage());
            }
        });
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
