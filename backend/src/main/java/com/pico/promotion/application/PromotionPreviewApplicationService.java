package com.pico.promotion.application;

import com.pico.experiment.domain.Experiment;
import com.pico.experiment.domain.ExperimentStatus;
import com.pico.port.ExperimentRepository;
import com.pico.port.ProjectRepository;
import com.pico.port.PromotionJournalPort;
import com.pico.port.SnapshotPort;
import com.pico.port.SnapshotRepository;
import com.pico.project.domain.Project;
import com.pico.shared.web.NotFoundException;
import com.pico.workspace.domain.Snapshot;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class PromotionPreviewApplicationService {
    private final ExperimentRepository experiments;
    private final ProjectRepository projects;
    private final SnapshotRepository snapshots;
    private final SnapshotPort snapshotPort;
    private final PromotionJournalPort promotionJournals;

    public PromotionPreviewApplicationService(ExperimentRepository experiments,
                                              ProjectRepository projects,
                                              SnapshotRepository snapshots,
                                              SnapshotPort snapshotPort,
                                              PromotionJournalPort promotionJournals) {
        this.experiments = experiments;
        this.projects = projects;
        this.snapshots = snapshots;
        this.snapshotPort = snapshotPort;
        this.promotionJournals = promotionJournals;
    }

    public PromotionPreview preview(UUID experimentId) {
        Experiment experiment = experiments.findById(experimentId)
                .orElseThrow(() -> new NotFoundException("Experiment not found: " + experimentId));
        Project project = projects.findById(experiment.projectId())
                .orElseThrow(() -> new NotFoundException("Project not found: " + experiment.projectId()));

        Snapshot base = experiment.baseSnapshotId() == null ? null
                : snapshots.findById(experiment.baseSnapshotId()).orElse(null);
        Snapshot candidate = experiment.resultSnapshotId() == null ? null
                : snapshots.findById(experiment.resultSnapshotId()).orElse(null);
        String baseFingerprint = base == null ? null : base.fingerprint();
        String candidateFingerprint = candidate == null ? null : candidate.fingerprint();
        String currentFingerprint = null;
        String inspectionFailure = null;
        try {
            currentFingerprint = snapshotPort.currentFingerprint(project);
        } catch (RuntimeException error) {
            inspectionFailure = "Canonical fingerprint unavailable: " + message(error);
        }

        String verificationStatus = experiment.verificationResult() == null ? "NOT_RUN"
                : experiment.verificationResult().passed() ? "PASSED" : "FAILED";
        boolean trustedVerification = experiment.verificationResult() != null;
        boolean promotedCandidateIsCurrent = experiment.status() == ExperimentStatus.PROMOTED
                && candidateFingerprint != null && candidateFingerprint.equals(currentFingerprint);
        boolean conflict = baseFingerprint != null && currentFingerprint != null
                && !baseFingerprint.equals(currentFingerprint) && !promotedCandidateIsCurrent;
        boolean unresolvedPromotion = !promotionJournals.findUnresolvedByProject(project.id()).isEmpty();
        String blockingReason = blockingReason(experiment, baseFingerprint,
                candidateFingerprint, verificationStatus, inspectionFailure, conflict, unresolvedPromotion);
        boolean promotable = experiment.status() == ExperimentStatus.VERIFIED
                && trustedVerification
                && "PASSED".equals(verificationStatus)
                && !conflict
                && !unresolvedPromotion
                && baseFingerprint != null
                && currentFingerprint != null
                && candidateFingerprint != null;

        return new PromotionPreview(baseFingerprint, currentFingerprint, candidateFingerprint,
                verificationStatus, trustedVerification, conflict, blockingReason, promotable);
    }

    private String blockingReason(Experiment experiment,
                                  String baseFingerprint,
                                  String candidateFingerprint,
                                  String verificationStatus,
                                  String inspectionFailure,
                                  boolean conflict,
                                  boolean unresolvedPromotion) {
        if (inspectionFailure != null) return inspectionFailure;
        if (baseFingerprint == null) return "Base snapshot is not available";
        if (unresolvedPromotion) return "An earlier promotion requires recovery before this project can be changed";
        if (experiment.status() == ExperimentStatus.PROMOTED && !conflict) return "Candidate is already canonical";
        if (conflict) {
            return (experiment.status() == ExperimentStatus.STALE
                    || experiment.status() == ExperimentStatus.RECOVERY_REQUIRED)
                    && experiment.failureReason() != null && !experiment.failureReason().isBlank()
                    ? experiment.failureReason()
                    : "Canonical changed after this experiment started";
        }
        if (candidateFingerprint == null) return "Final candidate has not been sealed";
        if ("NOT_RUN".equals(verificationStatus)) return "Trusted verification has not run";
        if ("FAILED".equals(verificationStatus)) {
            return experiment.failureReason() == null || experiment.failureReason().isBlank()
                    ? "Trusted verification failed" : experiment.failureReason();
        }
        if (experiment.status() == ExperimentStatus.STALE
                || experiment.status() == ExperimentStatus.RECOVERY_REQUIRED) {
            return experiment.failureReason() == null || experiment.failureReason().isBlank()
                    ? "Promotion requires reconciliation" : experiment.failureReason();
        }
        if (experiment.status() != ExperimentStatus.VERIFIED) {
            return "Experiment is " + experiment.status().name().replace('_', ' ');
        }
        return null;
    }

    private String message(RuntimeException error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }

    public record PromotionPreview(String baseFingerprint,
                                   String currentFingerprint,
                                   String finalCandidateFingerprint,
                                   String verificationStatus,
                                   boolean trustedVerification,
                                   boolean conflict,
                                   String blockingReason,
                                   boolean promotable) {
    }
}
