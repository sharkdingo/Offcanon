package com.pico.web;

import com.pico.application.ExperimentApplicationService;
import com.pico.application.ProjectApplicationService;
import com.pico.experiment.domain.Experiment;
import com.pico.project.domain.Project;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.pico.web.ApiDtos.CreateExperimentRequest;
import static com.pico.web.ApiDtos.CreateProjectRequest;
import static com.pico.web.ApiDtos.ExperimentResponse;
import static com.pico.web.ApiDtos.ProjectResponse;
import static com.pico.web.ApiDtos.SessionResponse;
import static com.pico.web.ApiDtos.CreateSessionRequest;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private final ProjectApplicationService projectService;
    private final ExperimentApplicationService experimentService;

    public ProjectController(ProjectApplicationService projectService, ExperimentApplicationService experimentService) {
        this.projectService = projectService;
        this.experimentService = experimentService;
    }

    @GetMapping
    public List<ProjectResponse> listProjects() {
        return projectService.list().stream().map(ProjectController::toResponse).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse createProject(@Valid @RequestBody CreateProjectRequest request) {
        return toResponse(projectService.register(request.name(), request.canonicalPath(), request.verificationCommands()));
    }

    @GetMapping("/{projectId}/experiments")
    public List<ExperimentResponse> listExperiments(@PathVariable UUID projectId) {
        return experimentService.listByProject(projectId).stream().map(ProjectController::toResponse).toList();
    }

    @GetMapping("/{projectId}/sessions")
    public List<SessionResponse> listSessions(@PathVariable UUID projectId) {
        return experimentService.listSessions(projectId).stream()
                .map(session -> new SessionResponse(session.id(), session.projectId(), session.title(), session.createdAt()))
                .toList();
    }

    @PostMapping("/{projectId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse createSession(@PathVariable UUID projectId,
                                         @Valid @RequestBody CreateSessionRequest request) {
        var session = experimentService.createSession(projectId, request.title());
        return new SessionResponse(session.id(), session.projectId(), session.title(), session.createdAt());
    }

    @PostMapping("/{projectId}/experiments")
    @ResponseStatus(HttpStatus.CREATED)
    public ExperimentResponse createExperiment(@PathVariable UUID projectId,
                                               @Valid @RequestBody CreateExperimentRequest request) {
        return toResponse(experimentService.create(projectId, request.sessionId(), request.sessionTitle(), request.task()));
    }

    private static ProjectResponse toResponse(Project project) {
        return new ProjectResponse(project.id(), project.name(), project.canonicalPath().toString(),
                project.verificationCommands(), project.createdAt());
    }

    private static ExperimentResponse toResponse(Experiment experiment) {
        return new ExperimentResponse(experiment.id(), experiment.projectId(), experiment.sessionId(), experiment.task(),
                experiment.status().name(), experiment.baseSnapshotId(), experiment.resultSnapshotId(),
                experiment.workspacePath() == null ? null : experiment.workspacePath().toString(),
                experiment.agentSummary(), experiment.failureReason(), experiment.createdAt(), experiment.version());
    }
}
