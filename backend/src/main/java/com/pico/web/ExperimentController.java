package com.pico.web;

import com.pico.application.ExperimentApplicationService;
import com.pico.experiment.domain.Experiment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.pico.web.ApiDtos.ExperimentResponse;

@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {
    private final ExperimentApplicationService experimentService;

    public ExperimentController(ExperimentApplicationService experimentService) {
        this.experimentService = experimentService;
    }

    @GetMapping("/{experimentId}")
    public ExperimentResponse get(@PathVariable UUID experimentId) {
        return toResponse(experimentService.get(experimentId));
    }

    @PostMapping("/{experimentId}/cancel")
    public ExperimentResponse cancel(@PathVariable UUID experimentId) {
        return toResponse(experimentService.cancel(experimentId));
    }

    private static ExperimentResponse toResponse(Experiment experiment) {
        return new ExperimentResponse(experiment.id(), experiment.projectId(), experiment.sessionId(), experiment.task(),
                experiment.status().name(), experiment.baseSnapshotId(),
                experiment.workspacePath() == null ? null : experiment.workspacePath().toString(),
                experiment.agentSummary(), experiment.failureReason(), experiment.createdAt(), experiment.version());
    }
}
