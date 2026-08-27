package com.offcanon.web;

import com.offcanon.application.ExperimentApplicationService;
import com.offcanon.agent.application.AgentApplicationService;
import com.offcanon.experiment.domain.Experiment;
import com.offcanon.port.EvidenceRepository;
import com.offcanon.port.DiffPort;
import com.offcanon.port.SnapshotRepository;
import com.offcanon.promotion.application.PromotionApplicationService;
import com.offcanon.promotion.application.PromotionPreviewApplicationService;
import com.offcanon.promotion.application.PromotionRecoveryService;
import com.offcanon.verification.domain.Evidence;
import com.offcanon.identity.web.IdentityContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.List;

import static com.offcanon.web.ApiDtos.ExperimentResponse;
import static com.offcanon.web.ApiDtos.EvidenceResponse;
import static com.offcanon.web.ApiDtos.PromotionResponse;
import static com.offcanon.web.ApiDtos.PromotionPreviewResponse;
import static com.offcanon.web.ApiDtos.PromotionReconcileResponse;
import static com.offcanon.web.ApiDtos.DiffEntryResponse;

@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {
    private final ExperimentApplicationService experimentService;
    private final AgentApplicationService agentService;
    private final EvidenceRepository evidenceRepository;
    private final PromotionApplicationService promotionService;
    private final PromotionPreviewApplicationService promotionPreviewService;
    private final PromotionRecoveryService promotionRecoveryService;
    private final DiffPort diffPort;
    private final SnapshotRepository snapshotRepository;
    private final IdentityContext identity;

    @Autowired
    public ExperimentController(ExperimentApplicationService experimentService,
                                AgentApplicationService agentService,
                                EvidenceRepository evidenceRepository,
                                PromotionApplicationService promotionService,
                                PromotionPreviewApplicationService promotionPreviewService,
                                PromotionRecoveryService promotionRecoveryService,
                                DiffPort diffPort,
                                SnapshotRepository snapshotRepository,
                                IdentityContext identity) {
        this.experimentService = experimentService;
        this.agentService = agentService;
        this.evidenceRepository = evidenceRepository;
        this.promotionService = promotionService;
        this.promotionPreviewService = promotionPreviewService;
        this.promotionRecoveryService = promotionRecoveryService;
        this.diffPort = diffPort;
        this.snapshotRepository = snapshotRepository;
        this.identity = identity;
    }

    /** Kept for narrow unit fixtures that instantiate the controller directly. */
    public ExperimentController(ExperimentApplicationService experimentService,
                                AgentApplicationService agentService,
                                EvidenceRepository evidenceRepository,
                                PromotionApplicationService promotionService,
                                PromotionPreviewApplicationService promotionPreviewService,
                                PromotionRecoveryService promotionRecoveryService,
                                DiffPort diffPort,
                                SnapshotRepository snapshotRepository) {
        this(experimentService, agentService, evidenceRepository, promotionService, promotionPreviewService,
                promotionRecoveryService, diffPort, snapshotRepository, null);
    }

    private UUID owner(HttpServletRequest request) {
        return identity == null ? com.offcanon.project.domain.Project.LEGACY_OWNER_ID : identity.ownerId(request);
    }

    @GetMapping("/{experimentId}")
    public ExperimentResponse get(@PathVariable UUID experimentId, HttpServletRequest request) {
        return toResponse(experimentService.get(experimentId, owner(request)));
    }

    @PostMapping("/{experimentId}/start")
    public ExperimentResponse start(@PathVariable UUID experimentId, HttpServletRequest request) {
        experimentService.get(experimentId, owner(request));
        return toResponse(agentService.start(experimentId));
    }

    @PostMapping("/{experimentId}/cancel")
    public ExperimentResponse cancel(@PathVariable UUID experimentId, HttpServletRequest request) {
        experimentService.get(experimentId, owner(request));
        return toResponse(agentService.cancel(experimentId));
    }

    @GetMapping("/{experimentId}/evidence")
    public List<EvidenceResponse> evidence(@PathVariable UUID experimentId, HttpServletRequest request) {
        experimentService.get(experimentId, owner(request));
        return evidenceRepository.findByExperimentId(experimentId).stream().map(ExperimentController::toEvidence).toList();
    }

    @GetMapping("/{experimentId}/diff")
    public List<DiffEntryResponse> diff(@PathVariable UUID experimentId, HttpServletRequest request) {
        Experiment experiment = experimentService.get(experimentId, owner(request));
        if (experiment.baseSnapshotId() == null || experiment.workspacePath() == null) return List.of();
        var snapshot = snapshotRepository.findById(experiment.baseSnapshotId())
                .orElseThrow(() -> new com.offcanon.shared.web.NotFoundException("Snapshot not found: " + experiment.baseSnapshotId()));
        java.nio.file.Path comparedWorkspace = experiment.resultSnapshotId() == null
                ? experiment.workspacePath()
                : snapshotRepository.findById(experiment.resultSnapshotId())
                .orElseThrow(() -> new com.offcanon.shared.web.NotFoundException("Snapshot not found: " + experiment.resultSnapshotId()))
                .materializedPath();
        return diffPort.compare(snapshot, comparedWorkspace).stream()
                .map(item -> new DiffEntryResponse(item.path(), item.change().name(), item.beforeBytes(), item.afterBytes(),
                        item.binary(), item.additions(), item.deletions(), item.patch()))
                .toList();
    }

    @PostMapping("/{experimentId}/promote")
    public PromotionResponse promote(@PathVariable UUID experimentId, HttpServletRequest request) {
        experimentService.get(experimentId, owner(request));
        PromotionApplicationService.PromotionOutcome outcome = promotionService.promote(experimentId);
        return new PromotionResponse(outcome.promoted(), outcome.status(), outcome.detail(), outcome.changedFiles(), outcome.fingerprint());
    }

    @GetMapping("/{experimentId}/promotion-preview")
    public PromotionPreviewResponse promotionPreview(@PathVariable UUID experimentId, HttpServletRequest request) {
        experimentService.get(experimentId, owner(request));
        PromotionPreviewApplicationService.PromotionPreview preview = promotionPreviewService.preview(experimentId);
        return new PromotionPreviewResponse(preview.baseFingerprint(), preview.currentFingerprint(),
                preview.finalCandidateFingerprint(), preview.verificationStatus(), preview.trustedVerification(),
                preview.conflict(), preview.blockingReason(), preview.promotable());
    }

    @PostMapping("/{experimentId}/promotion-reconcile")
    public PromotionReconcileResponse reconcilePromotion(@PathVariable UUID experimentId, HttpServletRequest request) {
        experimentService.get(experimentId, owner(request));
        PromotionRecoveryService.ManualReconciliation outcome = promotionRecoveryService.reconcileRequired(experimentId);
        return new PromotionReconcileResponse(outcome.promotionId(), outcome.experimentStatus(), outcome.journalPhase(),
                outcome.fingerprint(), outcome.detail());
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
