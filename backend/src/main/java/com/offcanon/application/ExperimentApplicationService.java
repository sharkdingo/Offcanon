package com.offcanon.application;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.port.ClockPort;
import com.offcanon.port.ExperimentRepository;
import com.offcanon.port.ProjectRepository;
import com.offcanon.port.SessionRepository;
import com.offcanon.port.SnapshotPort;
import com.offcanon.port.SnapshotRepository;
import com.offcanon.port.SessionRunLeasePort;
import com.offcanon.port.WorkspacePort;
import com.offcanon.port.PromotionLockPort;
import com.offcanon.project.domain.Project;
import com.offcanon.shared.web.ForbiddenException;
import com.offcanon.session.domain.Session;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.shared.web.NotFoundException;
import com.offcanon.workspace.domain.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
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
    private final SessionRunLeasePort sessionRunLease;
    private final PromotionLockPort promotionLock;
    private final ConcurrentHashMap<UUID, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    @Autowired
    public ExperimentApplicationService(ProjectRepository projectRepository,
                                        SessionRepository sessionRepository,
                                        ExperimentRepository experimentRepository,
                                        SnapshotRepository snapshotRepository,
                                        SnapshotPort snapshotPort,
                                        WorkspacePort workspacePort,
                                        ClockPort clock,
                                        SessionRunLeasePort sessionRunLease,
                                        PromotionLockPort promotionLock) {
        this.projectRepository = projectRepository;
        this.sessionRepository = sessionRepository;
        this.experimentRepository = experimentRepository;
        this.snapshotRepository = snapshotRepository;
        this.snapshotPort = snapshotPort;
        this.workspacePort = workspacePort;
        this.clock = clock;
        this.sessionRunLease = Objects.requireNonNull(sessionRunLease, "sessionRunLease");
        this.promotionLock = Objects.requireNonNull(promotionLock, "promotionLock");
    }

    public Experiment create(UUID ownerId, UUID projectId, UUID sessionId, String title, String task) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));
        requireOwner(project, ownerId);
        // Snapshot capture observes canonical files. Serialize the whole
        // preparation against promotion so the immutable base represents a
        // real canonical state rather than a tree observed mid-apply.
        return promotionLock.withProjectLock(project.id(), () -> {
            promotionLock.assertHeld(project.id());
            Project lockedProject = projectRepository.findById(project.id())
                    .orElseThrow(() -> new NotFoundException("Project not found: " + project.id()));
            requireOwner(lockedProject, ownerId);
            return createLocked(ownerId, lockedProject, sessionId, title, task);
        });
    }

    private Experiment createLocked(UUID ownerId,
                                    Project project,
                                    UUID sessionId,
                                    String title,
                                    String task) {
        assertPromotionLockHeld(project.id());
        UUID lockKey = sessionId == null ? project.id() : sessionId;
        ReentrantLock lock = sessionLocks.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
        lock.lock();
        try {
            assertPromotionLockHeld(project.id());
            Session session = resolveSession(project, sessionId, title);
            if (experimentRepository.hasRunningExperiment(session.id())) {
                throw new DomainException("SESSION_ALREADY_RUNNING", "A session can run only one experiment at a time");
            }

            Experiment experiment = Experiment.create(project.id(), session.id(), task, clock.now());
            SessionRunLeasePort.Lease lease = acquireCreationLease(experiment);
            try {
                assertPromotionLockHeld(project.id());
                lease.assertHeld();
                experimentRepository.save(experiment);
                lease.assertHeld();
                experiment.beginSnapshot();
                experimentRepository.save(experiment);
                assertPromotionLockHeld(project.id());
                lease.assertHeld();
                Snapshot snapshot = snapshotPort.capture(project);
                assertPromotionLockHeld(project.id());
                java.nio.file.Path workspace = null;
                try {
                    assertPromotionLockHeld(project.id());
                    lease.assertHeld();
                    snapshotRepository.save(snapshot);
                    lease.assertHeld();
                    workspace = workspacePort.materialize(snapshot, experiment.id());
                    assertPromotionLockHeld(project.id());
                    lease.assertHeld();
                    experiment.attachBase(snapshot.id(), workspace);
                    experimentRepository.save(experiment);
                    assertPromotionLockHeld(project.id());
                    lease.assertHeld();
                    return experiment;
                } catch (RuntimeException nested) {
                    cleanupPreparationFailure(experiment, snapshot, workspace);
                    throw nested;
                }
            } catch (RuntimeException error) {
                settleFailureBestEffort(experiment, error);
                throw error;
            } finally {
                releaseCreationLease(lease);
            }
        } finally {
            lock.unlock();
        }
    }

    public Experiment continueExperiment(UUID ownerId, UUID sourceExperimentId, String task) {
        Experiment initialSource = get(sourceExperimentId, ownerId);
        Project initialProject = projectRepository.findById(initialSource.projectId())
                .orElseThrow(() -> new NotFoundException("Project not found: " + initialSource.projectId()));
        return promotionLock.withProjectLock(initialProject.id(), () -> {
            promotionLock.assertHeld(initialProject.id());
            // Re-read under the same project lock used by Promotion. This closes
            // the window where a concurrent apply could make a successor fork
            // an already-obsolete canonical snapshot.
            Experiment source = get(sourceExperimentId, ownerId);
            Project project = projectRepository.findById(source.projectId())
                    .orElseThrow(() -> new NotFoundException("Project not found: " + source.projectId()));
            promotionLock.assertHeld(project.id());
            return continueExperimentLocked(ownerId, source, project, task);
        });
    }

    private Experiment continueExperimentLocked(UUID ownerId,
                                                Experiment source,
                                                Project project,
                                                String task) {
        Session session = sessionRepository.findById(source.sessionId())
                .orElseThrow(() -> new NotFoundException("Session not found: " + source.sessionId()));
        if (!session.projectId().equals(project.id())) {
            throw new DomainException("SESSION_PROJECT_MISMATCH", "Session belongs to a different project");
        }
        requireContinuable(source);

        ReentrantLock lock = sessionLocks.computeIfAbsent(session.id(), ignored -> new ReentrantLock());
        lock.lock();
        try {
            assertPromotionLockHeld(project.id());
            if (experimentRepository.hasRunningExperiment(session.id())) {
                throw new DomainException("SESSION_ALREADY_RUNNING", "A session can run only one experiment at a time");
            }
            String nextTask = task == null || task.isBlank()
                    ? "Continue the previous request."
                    : task.trim();
            Experiment successor = Experiment.continueFrom(project.id(), session.id(), source.id(), nextTask, clock.now());
            SessionRunLeasePort.Lease lease = acquireCreationLease(successor);
            try {
                assertPromotionLockHeld(project.id());
                lease.assertHeld();
                experimentRepository.save(successor);
                lease.assertHeld();
                successor.beginSnapshot();
                experimentRepository.save(successor);
                lease.assertHeld();
                Snapshot base = snapshotPort.capture(project);
                assertPromotionLockHeld(project.id());
                java.nio.file.Path workspace = null;
                ContinuationSeed seed = ContinuationSeed.none();
                try {
                    lease.assertHeld();
                    snapshotRepository.save(base);
                    lease.assertHeld();

                    seed = continuationSeed(project, source, base);
                    assertPromotionLockHeld(project.id());
                    lease.assertHeld();
                    workspace = materializeContinuation(base, seed, successor.id());
                    assertPromotionLockHeld(project.id());
                    lease.assertHeld();
                    successor.attachBase(base.id(), workspace);
                    experimentRepository.save(successor);
                    lease.assertHeld();
                    return successor;
                } catch (RuntimeException nested) {
                    cleanupPreparationFailure(successor, base, workspace);
                    throw nested;
                } finally {
                    discardTemporaryContinuationSeed(seed);
                }
            } catch (RuntimeException error) {
                settleFailureBestEffort(successor, error);
                throw error;
            } finally {
                releaseCreationLease(lease);
            }
        } finally {
            lock.unlock();
        }
    }

    private void assertPromotionLockHeld(UUID projectId) {
        promotionLock.assertHeld(projectId);
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

    public List<Experiment> listByProject(UUID projectId, UUID ownerId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));
        requireOwner(project, ownerId);
        return experimentRepository.findByProjectId(projectId);
    }

    public List<Session> listSessions(UUID projectId, UUID ownerId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));
        requireOwner(project, ownerId);
        return sessionRepository.findByProjectId(projectId);
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

    private void requireContinuable(Experiment source) {
        switch (source.status()) {
            case VERIFIED, REJECTED, STALE, PROMOTED, FAILED, CANCELLED -> {
                return;
            }
            default -> throw new DomainException("EXPERIMENT_NOT_CONTINUABLE",
                    "Experiment cannot be continued from " + source.status());
        }
    }

    private ContinuationSeed continuationSeed(Project project, Experiment source, Snapshot currentBase) {
        if (source.baseSnapshotId() == null) return ContinuationSeed.none();
        Snapshot sourceBase = snapshotRepository.findById(source.baseSnapshotId()).orElse(null);
        if (sourceBase == null || !sourceBase.fingerprint().equals(currentBase.fingerprint())) {
            return ContinuationSeed.none();
        }
        if (source.resultSnapshotId() != null) {
            Snapshot result = snapshotRepository.findById(source.resultSnapshotId()).orElse(null);
            if (result != null && result.projectId().equals(project.id())
                    && Files.isDirectory(result.materializedPath())) {
                return new ContinuationSeed(result, false);
            }
        }
        if (source.workspacePath() == null || !Files.isDirectory(source.workspacePath())) {
            return ContinuationSeed.none();
        }
        try {
            Snapshot draft = snapshotPort.captureWorkspace(project, source.workspacePath(), sourceBase.fingerprint());
            return new ContinuationSeed(draft, true);
        } catch (DomainException error) {
            // Runtime retention may evict a terminal partial workspace between
            // the existence check above and capture. The user intent remains
            // continuable from the fresh canonical base; losing an optional
            // draft must not make the continuation impossible.
            if ("WORKSPACE_SOURCE_MISSING".equals(error.code())
                    || "WORKSPACE_PATH_INVALID".equals(error.code())
                    || (!Files.isDirectory(source.workspacePath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && ("SNAPSHOT_FAILED".equals(error.code()) || "SNAPSHOT_RACED".equals(error.code())))) {
                return ContinuationSeed.none();
            }
            throw error;
        }
    }

    private java.nio.file.Path materializeContinuation(Snapshot base, ContinuationSeed seed, UUID experimentId) {
        if (seed.snapshot() == null) {
            return workspacePort.materialize(base, experimentId);
        }
        return workspacePort.materializeContinuation(base, seed.snapshot(), experimentId);
    }

    private void discardTemporaryContinuationSeed(ContinuationSeed seed) {
        if (seed == null || !seed.temporary() || seed.snapshot() == null) return;
        try {
            snapshotPort.discard(seed.snapshot());
        } catch (RuntimeException ignored) {
            // Runtime retention can remove the temporary continuation snapshot.
        }
    }

    private void discardSnapshotBestEffort(Snapshot snapshot) {
        if (snapshot == null) return;
        try {
            snapshotPort.discard(snapshot);
        } catch (RuntimeException ignored) {
            // A failed persistence attempt leaves only a cache artifact; the
            // retention service can remove it if immediate discard is unavailable.
        }
    }

    private void discardWorkspaceBestEffort(java.nio.file.Path workspace) {
        if (workspace == null) return;
        try {
            workspacePort.discard(workspace);
        } catch (RuntimeException ignored) {
            // Runtime retention remains the final cleanup boundary when an
            // adapter cannot remove the artifact immediately.
        }
    }

    /**
     * Release preparation artifacts unless the final lifecycle save actually
     * attached them to the durable Experiment row. Reloading the row is
     * important: a repository can reject a detached stale object while a
     * concurrent or ambiguous write may already have committed the attachment.
     */
    private void cleanupPreparationFailure(Experiment local,
                                           Snapshot snapshot,
                                           java.nio.file.Path workspace) {
        boolean attached = false;
        try {
            Experiment persisted = experimentRepository.findById(local.id()).orElse(null);
            attached = persisted != null
                    && snapshot != null
                    && snapshot.id().equals(persisted.baseSnapshotId())
                    && workspace != null
                    && persisted.workspacePath() != null
                    && persisted.workspacePath().toAbsolutePath().normalize()
                    .equals(workspace.toAbsolutePath().normalize());
        } catch (RuntimeException ignored) {
            // If durable state cannot be inspected, retain the artifacts.
            // Runtime retention is safer than deleting a possibly referenced tree.
            attached = true;
        }
        if (!attached) {
            discardWorkspaceBestEffort(workspace);
            discardSnapshotBestEffort(snapshot);
        }
    }

    private void settleFailureBestEffort(Experiment experiment, RuntimeException original) {
        String reason = original.getMessage() == null
                ? original.getClass().getSimpleName() : original.getMessage();
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Experiment current = experimentRepository.findById(experiment.id()).orElse(experiment);
                if (!canSettleFailure(current.status())) return;
                current.fail(reason);
                experimentRepository.save(current);
                return;
            } catch (DomainException settlementFailure) {
                if (!"EXPERIMENT_VERSION_CONFLICT".equals(settlementFailure.code()) || attempt == 3) {
                    original.addSuppressed(settlementFailure);
                    return;
                }
            } catch (RuntimeException settlementFailure) {
                original.addSuppressed(settlementFailure);
                return;
            }
        }
    }

    private boolean canSettleFailure(ExperimentStatus status) {
        return status == ExperimentStatus.CREATED
                || status == ExperimentStatus.SNAPSHOTTING;
    }

    private record ContinuationSeed(Snapshot snapshot, boolean temporary) {
        private static ContinuationSeed none() {
            return new ContinuationSeed(null, false);
        }
    }

    private void requireOwner(Project project, UUID ownerId) {
        if (ownerId == null || !project.ownerId().equals(ownerId)) {
            throw new ForbiddenException("You do not own this project");
        }
    }

    private SessionRunLeasePort.Lease acquireCreationLease(Experiment experiment) {
        return sessionRunLease.tryAcquire(experiment.sessionId(), experiment.id())
                .orElseThrow(() -> new DomainException("SESSION_ALREADY_RUNNING",
                        "A session is currently being initialized or run"));
    }

    private void releaseCreationLease(SessionRunLeasePort.Lease lease) {
        try {
            lease.release();
        } catch (RuntimeException ignored) {
            // The handle is acquisition-specific and will expire; do not mask
            // the authoritative creation result with a release outage.
        }
    }
}
