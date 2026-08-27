package com.pico.application;

import com.pico.experiment.domain.Experiment;
import com.pico.port.ClockPort;
import com.pico.port.ExperimentRepository;
import com.pico.port.ProjectRepository;
import com.pico.port.SessionRepository;
import com.pico.port.SnapshotPort;
import com.pico.port.SnapshotRepository;
import com.pico.port.WorkspacePort;
import com.pico.project.domain.Project;
import com.pico.session.domain.Session;
import com.pico.shared.domain.DomainException;
import com.pico.shared.web.NotFoundException;
import com.pico.workspace.domain.Snapshot;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ExperimentApplicationService {
    private final ProjectRepository projectRepository;
    private final SessionRepository sessionRepository;
    private final ExperimentRepository experimentRepository;
    private final SnapshotRepository snapshotRepository;
    private final SnapshotPort snapshotPort;
    private final WorkspacePort workspacePort;
    private final ClockPort clock;

    public ExperimentApplicationService(ProjectRepository projectRepository,
                                        SessionRepository sessionRepository,
                                        ExperimentRepository experimentRepository,
                                        SnapshotRepository snapshotRepository,
                                        SnapshotPort snapshotPort,
                                        WorkspacePort workspacePort,
                                        ClockPort clock) {
        this.projectRepository = projectRepository;
        this.sessionRepository = sessionRepository;
        this.experimentRepository = experimentRepository;
        this.snapshotRepository = snapshotRepository;
        this.snapshotPort = snapshotPort;
        this.workspacePort = workspacePort;
        this.clock = clock;
    }

    public Experiment create(UUID projectId, UUID sessionId, String title, String task) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));
        Session session = resolveSession(project, sessionId, title);
        if (experimentRepository.hasRunningExperiment(session.id())) {
            throw new DomainException("SESSION_ALREADY_RUNNING", "A session can run only one experiment at a time");
        }

        Experiment experiment = Experiment.create(project.id(), session.id(), task, clock.now());
        experimentRepository.save(experiment);
        try {
            experiment.beginSnapshot();
            experimentRepository.save(experiment);
            Snapshot snapshot = snapshotPort.capture(project);
            snapshotRepository.save(snapshot);
            experiment.attachBase(snapshot.id(), workspacePort.materialize(snapshot, experiment.id()));
            experimentRepository.save(experiment);
            return experiment;
        } catch (RuntimeException error) {
            experiment.fail(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            experimentRepository.save(experiment);
            throw error;
        }
    }

    public Experiment get(UUID experimentId) {
        return experimentRepository.findById(experimentId)
                .orElseThrow(() -> new NotFoundException("Experiment not found: " + experimentId));
    }

    public List<Experiment> listByProject(UUID projectId) {
        if (projectRepository.findById(projectId).isEmpty()) {
            throw new NotFoundException("Project not found: " + projectId);
        }
        return experimentRepository.findByProjectId(projectId);
    }

    public Experiment cancel(UUID experimentId) {
        Experiment experiment = get(experimentId);
        experiment.cancel();
        return experimentRepository.save(experiment);
    }

    private Session resolveSession(Project project, UUID sessionId, String title) {
        if (sessionId != null) {
            return sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new NotFoundException("Session not found: " + sessionId));
        }
        String sessionTitle = title == null || title.isBlank() ? "Session " + (sessionRepository.findByProjectId(project.id()).size() + 1) : title;
        return sessionRepository.save(Session.create(project.id(), sessionTitle, clock.now()));
    }
}
