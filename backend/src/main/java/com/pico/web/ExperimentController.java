package com.pico.web;

import com.pico.application.ExperimentApplicationService;
import com.pico.agent.application.AgentApplicationService;
import com.pico.experiment.domain.Experiment;
import com.pico.port.EvidenceRepository;
import com.pico.promotion.application.PromotionApplicationService;
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

@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {
    private final ExperimentApplicationService experimentService;
    private final AgentApplicationService agentService;
    private final EvidenceRepository evidenceRepository;
    private final PromotionApplicationService promotionService;

    public ExperimentController(ExperimentApplicationService experimentService,
                                AgentApplicationService agentService,
                                EvidenceRepository evidenceRepository,
                                PromotionApplicationService promotionService) {
        this.experimentService = experimentService;
        this.agentService = agentService;
        this.evidenceRepository = evidenceRepository;
        this.promotionService = promotionService;
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

    @PostMapping("/{experimentId}/promote")
    public PromotionResponse promote(@PathVariable UUID experimentId) {
        PromotionApplicationService.PromotionOutcome outcome = promotionService.promote(experimentId);
        return new PromotionResponse(outcome.promoted(), outcome.status(), outcome.detail(), outcome.changedFiles(), outcome.fingerprint());
    }

    private static ExperimentResponse toResponse(Experiment experiment) {
        return new ExperimentResponse(experiment.id(), experiment.projectId(), experiment.sessionId(), experiment.task(),
                experiment.status().name(), experiment.baseSnapshotId(),
                experiment.workspacePath() == null ? null : experiment.workspacePath().toString(),
                experiment.agentSummary(), experiment.failureReason(), experiment.createdAt(), experiment.version());
    }

    private static EvidenceResponse toEvidence(Evidence evidence) {
        return new EvidenceResponse(evidence.id(), evidence.experimentId(), evidence.snapshotId(), evidence.kind(),
                evidence.command(), evidence.cwd(), evidence.exitCode(), evidence.stdout(), evidence.stderr(),
                evidence.startedAt(), evidence.completedAt(), evidence.duration().toMillis(), evidence.timedOut(), evidence.trusted());
    }
}
