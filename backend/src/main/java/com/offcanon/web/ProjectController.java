package com.offcanon.web;

import com.offcanon.application.ExperimentApplicationService;
import com.offcanon.application.ProjectApplicationService;
import com.offcanon.experiment.domain.Experiment;
import com.offcanon.project.domain.Project;
import com.offcanon.identity.web.IdentityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.offcanon.web.ApiDtos.CreateExperimentRequest;
import static com.offcanon.web.ApiDtos.CreateProjectRequest;
import static com.offcanon.web.ApiDtos.UpdateProjectRequest;
import static com.offcanon.web.ApiDtos.ExperimentResponse;
import static com.offcanon.web.ApiDtos.ProjectResponse;
import static com.offcanon.web.ApiDtos.ProjectRegistrationResponse;
import static com.offcanon.web.ApiDtos.SessionResponse;
import static com.offcanon.web.ApiDtos.CreateSessionRequest;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private final ProjectApplicationService projectService;
    private final ExperimentApplicationService experimentService;
    private final IdentityContext identity;

    @Autowired
    public ProjectController(ProjectApplicationService projectService,
                             ExperimentApplicationService experimentService,
                             IdentityContext identity) {
        this.projectService = projectService;
        this.experimentService = experimentService;
        this.identity = identity;
    }

    @GetMapping
    public List<ProjectResponse> listProjects(HttpServletRequest request) {
        return projectService.list(identity.ownerId(request)).stream().map(ProjectController::toResponse).toList();
    }

    @PostMapping
    public ResponseEntity<ProjectRegistrationResponse> createProject(
            @Valid @RequestBody CreateProjectRequest request,
            HttpServletRequest httpRequest) {
        var result = projectService.registerWithOutcome(identity.ownerId(httpRequest), request.name(),
                request.canonicalPath(), request.verificationCommands());
        Project project = result.project();
        var response = new ProjectRegistrationResponse(project.id(), project.name(),
                project.canonicalPath().toString(), project.verificationCommands(),
                project.createdAt(), result.reopened());
        return ResponseEntity.status(result.reopened() ? HttpStatus.OK : HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{projectId}")
    public ProjectResponse updateProject(@PathVariable UUID projectId,
                                         @Valid @RequestBody UpdateProjectRequest request,
                                         HttpServletRequest httpRequest) {
        return toResponse(projectService.update(identity.ownerId(httpRequest), projectId,
                request.name(), request.canonicalPath(), request.verificationCommands()));
    }

    @GetMapping("/{projectId}/experiments")
    public List<ExperimentResponse> listExperiments(@PathVariable UUID projectId, HttpServletRequest request) {
        return experimentService.listByProject(projectId, identity.ownerId(request)).stream().map(ProjectController::toExperimentResponse).toList();
    }

    @GetMapping("/{projectId}/sessions")
    public List<SessionResponse> listSessions(@PathVariable UUID projectId, HttpServletRequest request) {
        return experimentService.listSessions(projectId, identity.ownerId(request)).stream()
                .map(session -> new SessionResponse(session.id(), session.projectId(), session.title(), session.createdAt()))
                .toList();
    }

    @PostMapping("/{projectId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse createSession(@PathVariable UUID projectId,
                                         @Valid @RequestBody CreateSessionRequest request,
                                         HttpServletRequest httpRequest) {
        var session = experimentService.createSession(identity.ownerId(httpRequest), projectId, request.title());
        return new SessionResponse(session.id(), session.projectId(), session.title(), session.createdAt());
    }

    @PostMapping("/{projectId}/experiments")
    @ResponseStatus(HttpStatus.CREATED)
    public ExperimentResponse createExperiment(@PathVariable UUID projectId,
                                               @Valid @RequestBody CreateExperimentRequest request,
                                               HttpServletRequest httpRequest) {
        return toExperimentResponse(experimentService.create(identity.ownerId(httpRequest), projectId, request.sessionId(), request.sessionTitle(), request.task()));
    }

    private static ProjectResponse toResponse(Project project) {
        return new ProjectResponse(project.id(), project.name(), project.canonicalPath().toString(),
                project.verificationCommands(), project.createdAt());
    }

    private static ExperimentResponse toExperimentResponse(Experiment experiment) {
        return new ExperimentResponse(experiment.id(), experiment.projectId(), experiment.sessionId(),
                experiment.continuedFromExperimentId(), experiment.task(),
                experiment.status().name(), experiment.baseSnapshotId(), experiment.resultSnapshotId(),
                experiment.workspacePath() == null ? null : experiment.workspacePath().toString(),
                experiment.agentSummary(), experiment.failureReason(), experiment.createdAt(), experiment.version());
    }
}
