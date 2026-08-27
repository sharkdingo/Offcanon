package com.offcanon.application;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.port.ClockPort;
import com.offcanon.port.ExperimentRepository;
import com.offcanon.port.ProjectRepository;
import com.offcanon.port.SessionRepository;
import com.offcanon.port.SnapshotPort;
import com.offcanon.port.SnapshotRepository;
import com.offcanon.port.WorkspacePort;
import com.offcanon.project.domain.Project;
import com.offcanon.shared.web.ForbiddenException;
import com.offcanon.session.domain.Session;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.shared.web.NotFoundException;
import com.offcanon.workspace.domain.Snapshot;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class ExperimentApplicationService {
    private final ProjectRepository projectRepository;
    private final SessionRepository sessionRepository;
    private final ExperimentRepository experimentRepository;
    private final SnapshotRepository snapshotRepository;
    private final SnapshotPort snapshotPort;
    private final WorkspacePort workspacePort;
    private final ClockPort clock;
    private final ConcurrentHashMap<UUID, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

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
        return create(Project.LEGACY_OWNER_ID, projectId, sessionId, title, task);
    }

    public Experiment create(UUID ownerId, UUID projectId, UUID sessionId, String title, String task) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));
        requireOwner(project, ownerId);
        UUID lockKey = sessionId == null ? project.id() : sessionId;
        ReentrantLock lock = sessionLocks.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
        lock.lock();
        try {
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
        } finally {
            lock.unlock();
        }
    }

    public Experiment get(UUID experimentId) {
        return experimentRepository.findById(experimentId)
                .orElseThrow(() -> new NotFoundException("Experiment not found: " + experimentId));
    }

    public Experiment get(UUID experimentId, UUID ownerId) {
        Experiment experiment = get(experimentId);
        Project project = projectRepository.findById(experiment.projectId())
                .orElseThrow(() -> new NotFoundException("Project not found: " + experiment.projectId()));
        requireOwner(project, ownerId);
        return experiment;
    }

    public List<Experiment> listByProject(UUID projectId) {
        return listByProject(projectId, Project.LEGACY_OWNER_ID);
    }

    public List<Experiment> listByProject(UUID projectId, UUID ownerId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));
        requireOwner(project, ownerId);
        return experimentRepository.findByProjectId(projectId);
    }

    public List<Session> listSessions(UUID projectId) {
        return listSessions(projectId, Project.LEGACY_OWNER_ID);
    }

    public List<Session> listSessions(UUID projectId, UUID ownerId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));
        requireOwner(project, ownerId);
        return sessionRepository.findByProjectId(projectId);
    }

    public Session createSession(UUID projectId, String title) {
        return createSession(Project.LEGACY_OWNER_ID, projectId, title);
    }

    public Session createSession(UUID ownerId, UUID projectId, String title) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));
        requireOwner(project, ownerId);
        String normalizedTitle = title == null || title.isBlank()
                ? "Session " + (sessionRepository.findByProjectId(project.id()).size() + 1)
                : title;
        return sessionRepository.save(Session.create(project.id(), normalizedTitle, clock.now()));
    }

    public Experiment cancel(UUID experimentId) {
        Experiment experiment = get(experimentId);
        experiment.cancel();
        return experimentRepository.save(experiment);
    }

    private Session resolveSession(Project project, UUID sessionId, String title) {
        if (sessionId != null) {
            Session session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new NotFoundException("Session not found: " + sessionId));
            if (!session.projectId().equals(project.id())) {
                throw new DomainException("SESSION_PROJECT_MISMATCH", "Session belongs to a different project");
            }
            return session;
        }
        String sessionTitle = title == null || title.isBlank() ? "Session " + (sessionRepository.findByProjectId(project.id()).size() + 1) : title;
        return sessionRepository.save(Session.create(project.id(), sessionTitle, clock.now()));
    }

    private void requireOwner(Project project, UUID ownerId) {
        if (ownerId == null || !project.ownerId().equals(ownerId)) {
            throw new ForbiddenException("You do not own this project");
        }
    }
}
