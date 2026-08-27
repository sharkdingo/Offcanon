package com.pico.web;

import com.pico.application.ExperimentApplicationService;
import com.pico.agent.application.AgentApplicationService;
import com.pico.experiment.domain.Experiment;
import com.pico.port.EvidenceRepository;
import com.pico.port.DiffPort;
import com.pico.port.SnapshotRepository;
import com.pico.promotion.application.PromotionApplicationService;
import com.pico.promotion.application.PromotionPreviewApplicationService;
import com.pico.verification.domain.Evidence;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.List;

import static com.pico.web.ApiDtos.ExperimentResponse;
import static com.pico.web.ApiDtos.EvidenceResponse;
import static com.pico.web.ApiDtos.PromotionResponse;
import static com.pico.web.ApiDtos.PromotionPreviewResponse;
import static com.pico.web.ApiDtos.DiffEntryResponse;

@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {
    private final ExperimentApplicationService experimentService;
    private final AgentApplicationService agentService;
    private final EvidenceRepository evidenceRepository;
    private final PromotionApplicationService promotionService;
    private final PromotionPreviewApplicationService promotionPreviewService;
    private final DiffPort diffPort;
    private final SnapshotRepository snapshotRepository;

    public ExperimentController(ExperimentApplicationService experimentService,
                                AgentApplicationService agentService,
                                EvidenceRepository evidenceRepository,
                                PromotionApplicationService promotionService,
                                PromotionPreviewApplicationService promotionPreviewService,
                                DiffPort diffPort,
                                SnapshotRepository snapshotRepository) {
        this.experimentService = experimentService;
        this.agentService = agentService;
        this.evidenceRepository = evidenceRepository;
        this.promotionService = promotionService;
        this.promotionPreviewService = promotionPreviewService;
        this.diffPort = diffPort;
        this.snapshotRepository = snapshotRepository;
    }

    @GetMapping("/{experimentId}")
    public ExperimentResponse get(@PathVariable UUID experimentId) {
        return toResponse(experimentService.get(experimentId));
    }

    @PostMapping("/{experimentId}/start")
    public ExperimentResponse start(@PathVariable UUID experimentId) {
        return toResponse(agentService.start(experimentId));
    }

    @PostMapping("/{experimentId}/cancel")
    public ExperimentResponse cancel(@PathVariable UUID experimentId) {
        return toResponse(agentService.cancel(experimentId));
    }

    @GetMapping("/{experimentId}/evidence")
    public List<EvidenceResponse> evidence(@PathVariable UUID experimentId) {
        experimentService.get(experimentId);
        return evidenceRepository.findByExperimentId(experimentId).stream().map(ExperimentController::toEvidence).toList();
    }

    @GetMapping("/{experimentId}/diff")
    public List<DiffEntryResponse> diff(@PathVariable UUID experimentId) {
        Experiment experiment = experimentService.get(experimentId);
        if (experiment.baseSnapshotId() == null || experiment.workspacePath() == null) return List.of();
        var snapshot = snapshotRepository.findById(experiment.baseSnapshotId())
                .orElseThrow(() -> new com.pico.shared.web.NotFoundException("Snapshot not found: " + experiment.baseSnapshotId()));
        java.nio.file.Path comparedWorkspace = experiment.resultSnapshotId() == null
                ? experiment.workspacePath()
                : snapshotRepository.findById(experiment.resultSnapshotId())
                .orElseThrow(() -> new com.pico.shared.web.NotFoundException("Snapshot not found: " + experiment.resultSnapshotId()))
                .materializedPath();
        return diffPort.compare(snapshot, comparedWorkspace).stream()
                .map(item -> new DiffEntryResponse(item.path(), item.change().name(), item.beforeBytes(), item.afterBytes(),
                        item.binary(), item.additions(), item.deletions(), item.patch()))
                .toList();
    }

    @PostMapping("/{experimentId}/promote")
    public PromotionResponse promote(@PathVariable UUID experimentId) {
        PromotionApplicationService.PromotionOutcome outcome = promotionService.promote(experimentId);
        return new PromotionResponse(outcome.promoted(), outcome.status(), outcome.detail(), outcome.changedFiles(), outcome.fingerprint());
    }

    @GetMapping("/{experimentId}/promotion-preview")
    public PromotionPreviewResponse promotionPreview(@PathVariable UUID experimentId) {
        PromotionPreviewApplicationService.PromotionPreview preview = promotionPreviewService.preview(experimentId);
        return new PromotionPreviewResponse(preview.baseFingerprint(), preview.currentFingerprint(),
                preview.finalCandidateFingerprint(), preview.verificationStatus(), preview.trustedVerification(),
                preview.conflict(), preview.blockingReason(), preview.promotable());
    }

    private static ExperimentResponse toResponse(Experiment experiment) {
        return new ExperimentResponse(experiment.id(), experiment.projectId(), experiment.sessionId(), experiment.task(),
                experiment.status().name(), experiment.baseSnapshotId(), experiment.resultSnapshotId(),
                experiment.workspacePath() == null ? null : experiment.workspacePath().toString(),
                experiment.agentSummary(), experiment.failureReason(), experiment.createdAt(), experiment.version());
    }

    private static EvidenceResponse toEvidence(Evidence evidence) {
        return new EvidenceResponse(evidence.id(), evidence.experimentId(), evidence.snapshotId(), evidence.kind(),
                evidence.command(), evidence.cwd(), evidence.exitCode(), evidence.stdout(), evidence.stderr(),
                evidence.startedAt(), evidence.completedAt(), evidence.duration().toMillis(), evidence.timedOut(), evidence.trusted(),
                evidence.environmentProfile(), evidence.cancelled());
    }
}
