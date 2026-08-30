package com.offcanon.application;

import com.offcanon.agent.application.AgentRecoveryService;
import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.infrastructure.memory.InMemoryEventSink;
import com.offcanon.infrastructure.memory.InMemoryExperimentRepository;
import com.offcanon.infrastructure.memory.InMemoryProjectRepository;
import com.offcanon.infrastructure.memory.InMemorySessionRepository;
import com.offcanon.infrastructure.memory.InMemorySessionRunLease;
import com.offcanon.infrastructure.memory.InMemorySnapshotRepository;
import com.offcanon.port.ExperimentRepository;
import com.offcanon.port.PromotionLockPort;
import com.offcanon.port.SnapshotPort;
import com.offcanon.port.WorkspacePort;
import com.offcanon.project.domain.Project;
import com.offcanon.session.domain.Session;
import com.offcanon.workspace.domain.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExperimentPreparationConcurrencyTest {
    @TempDir
    Path temp;

    @Test
    void activeCreatorLeasePreventsRecoveryFromFailingSnapshottingExperiment() throws Exception {
        Fixture fixture = fixture(new InMemoryExperimentRepository());
        CountDownLatch captureEntered = new CountDownLatch(1);
        CountDownLatch allowCapture = new CountDownLatch(1);
        Snapshot snapshot = fixture.snapshot("live-creator");
        SnapshotPort snapshotPort = mock(SnapshotPort.class);
        when(snapshotPort.capture(fixture.project)).thenAnswer(ignored -> {
            captureEntered.countDown();
            assertTrue(allowCapture.await(5, TimeUnit.SECONDS));
            return snapshot;
        });
        WorkspacePort workspaces = mock(WorkspacePort.class);
        when(workspaces.materialize(eq(snapshot), any(UUID.class))).thenReturn(temp.resolve("live-workspace"));
        ExperimentApplicationService service = fixture.service(snapshotPort, workspaces);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<Experiment> creation = executor.submit(() -> service.create(
                    fixture.owner, fixture.project.id(), fixture.session.id(), null, "prepare safely"));
            assertTrue(captureEntered.await(5, TimeUnit.SECONDS));
            Experiment snapshotting = fixture.experiments.findByProjectId(fixture.project.id()).getFirst();
            assertEquals(ExperimentStatus.SNAPSHOTTING, snapshotting.status());

            AgentRecoveryService recovery = new AgentRecoveryService(fixture.projects, fixture.experiments,
                    fixture.leases, new InMemoryEventSink());
            assertEquals(0, recovery.recoverInterruptedInitialization());
            assertEquals(ExperimentStatus.SNAPSHOTTING,
                    fixture.experiments.findById(snapshotting.id()).orElseThrow().status());

            allowCapture.countDown();
            assertEquals(ExperimentStatus.READY_TO_RUN, creation.get(5, TimeUnit.SECONDS).status());
        } finally {
            allowCapture.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void leaseLossDuringMaterializationFailsPreparationAndDiscardsArtifacts() {
        Fixture fixture = fixture(new InMemoryExperimentRepository());
        Snapshot snapshot = fixture.snapshot("lease-loss");
        Path workspace = temp.resolve("lost-workspace");
        SnapshotPort snapshotPort = mock(SnapshotPort.class);
        when(snapshotPort.capture(fixture.project)).thenReturn(snapshot);
        WorkspacePort workspaces = mock(WorkspacePort.class);
        when(workspaces.materialize(eq(snapshot), any(UUID.class))).thenAnswer(invocation -> {
            fixture.leases.revoke(fixture.session.id(), invocation.getArgument(1));
            return workspace;
        });
        ExperimentApplicationService service = fixture.service(snapshotPort, workspaces);

        com.offcanon.shared.domain.DomainException error = assertThrows(
                com.offcanon.shared.domain.DomainException.class,
                () -> service.create(fixture.owner, fixture.project.id(), fixture.session.id(), null, "lose lease"));

        assertEquals("SESSION_RUN_LEASE_LOST", error.code());
        Experiment failed = fixture.experiments.findByProjectId(fixture.project.id()).getFirst();
        assertEquals(ExperimentStatus.FAILED, failed.status());
        verify(workspaces).discard(workspace);
        verify(snapshotPort).discard(snapshot);
    }

    @Test
    void uncommittedFinalSaveFailureFailsExperimentAndDiscardsArtifacts() {
        FailingFinalSaveRepository repository = new FailingFinalSaveRepository(FailureMode.BEFORE_COMMIT);
        Fixture fixture = fixture(repository);
        Snapshot snapshot = fixture.snapshot("save-failure");
        Path workspace = temp.resolve("failed-save-workspace");
        SnapshotPort snapshotPort = mock(SnapshotPort.class);
        when(snapshotPort.capture(fixture.project)).thenReturn(snapshot);
        WorkspacePort workspaces = mock(WorkspacePort.class);
        when(workspaces.materialize(eq(snapshot), any(UUID.class))).thenReturn(workspace);
        ExperimentApplicationService service = fixture.service(snapshotPort, workspaces);

        assertThrows(IllegalStateException.class, () -> service.create(
                fixture.owner, fixture.project.id(), fixture.session.id(), null, "fail final save"));

        Experiment failed = repository.findByProjectId(fixture.project.id()).getFirst();
        assertEquals(ExperimentStatus.FAILED, failed.status());
        verify(workspaces).discard(workspace);
        verify(snapshotPort).discard(snapshot);
    }

    @Test
    void ambiguousFinalSaveDoesNotOverwriteAWorkerThatAlreadyAdvancedState() {
        FailingFinalSaveRepository repository = new FailingFinalSaveRepository(FailureMode.AFTER_COMMIT_AND_START);
        Fixture fixture = fixture(repository);
        Snapshot snapshot = fixture.snapshot("ambiguous-save");
        Path workspace = temp.resolve("committed-workspace");
        SnapshotPort snapshotPort = mock(SnapshotPort.class);
        when(snapshotPort.capture(fixture.project)).thenReturn(snapshot);
        WorkspacePort workspaces = mock(WorkspacePort.class);
        when(workspaces.materialize(eq(snapshot), any(UUID.class))).thenReturn(workspace);
        ExperimentApplicationService service = fixture.service(snapshotPort, workspaces);

        assertThrows(IllegalStateException.class, () -> service.create(
                fixture.owner, fixture.project.id(), fixture.session.id(), null, "ambiguous final save"));

        Experiment running = repository.findByProjectId(fixture.project.id()).getFirst();
        assertEquals(ExperimentStatus.RUNNING, running.status());
        assertEquals(snapshot.id(), running.baseSnapshotId());
        assertEquals(workspace.toAbsolutePath().normalize(), running.workspacePath());
        verify(workspaces, never()).discard(workspace);
        verify(snapshotPort, never()).discard(snapshot);
    }

    @Test
    void snapshotCaptureRunsInsideTheProjectPromotionLock() {
        Fixture fixture = fixture(new InMemoryExperimentRepository());
        Snapshot snapshot = fixture.snapshot("locked-capture");
        AtomicBoolean captureObservedLock = new AtomicBoolean(false);
        SnapshotPort snapshotPort = mock(SnapshotPort.class);
        when(snapshotPort.capture(fixture.project)).thenAnswer(ignored -> {
            captureObservedLock.set(true);
            return snapshot;
        });
        WorkspacePort workspaces = mock(WorkspacePort.class);
        when(workspaces.materialize(eq(snapshot), any(UUID.class))).thenReturn(temp.resolve("locked-workspace"));
        PromotionLockPort projectLock = new PromotionLockPort() {
            private final ThreadLocal<Boolean> held = ThreadLocal.withInitial(() -> false);

            @Override
            public <T> T withProjectLock(UUID projectId, java.util.function.Supplier<T> action) {
                assertTrue(held.get() == false, "project lock must not be re-entered during preparation");
                held.set(true);
                try {
                    return action.get();
                } finally {
                    held.remove();
                }
            }

            @Override
            public void assertHeld(UUID projectId) {
                assertTrue(held.get(), "canonical observation must hold the project lock");
            }
        };
        ExperimentApplicationService service = new ExperimentApplicationService(
                fixture.projects, fixture.sessions, fixture.experiments, fixture.snapshots,
                snapshotPort, workspaces, Instant::now, fixture.leases, projectLock);

        Experiment created = service.create(fixture.owner, fixture.project.id(), fixture.session.id(), null,
                "capture under lock");

        assertTrue(captureObservedLock.get());
        assertEquals(ExperimentStatus.READY_TO_RUN, created.status());
    }

    private Fixture fixture(ExperimentRepository experiments) {
        UUID owner = UUID.randomUUID();
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        Project project = projects.save(Project.create(owner, "demo", temp, List.of(), Instant.now()));
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        Session session = sessions.save(Session.create(project.id(), "session", Instant.now()));
        return new Fixture(owner, project, session, projects, sessions, experiments,
                new InMemorySnapshotRepository(), new InMemorySessionRunLease());
    }

    private enum FailureMode {
        BEFORE_COMMIT,
        AFTER_COMMIT_AND_START
    }

    private static final class FailingFinalSaveRepository implements ExperimentRepository {
        private final InMemoryExperimentRepository delegate = new InMemoryExperimentRepository();
        private final FailureMode mode;
        private final AtomicBoolean failed = new AtomicBoolean();

        private FailingFinalSaveRepository(FailureMode mode) {
            this.mode = mode;
        }

        @Override
        public Experiment save(Experiment experiment) {
            if (experiment.status() == ExperimentStatus.READY_TO_RUN && failed.compareAndSet(false, true)) {
                if (mode == FailureMode.BEFORE_COMMIT) {
                    throw new IllegalStateException("final save rejected");
                }
                delegate.save(experiment);
                Experiment running = delegate.findById(experiment.id()).orElseThrow();
                running.start();
                delegate.save(running);
                throw new IllegalStateException("final save outcome was ambiguous");
            }
            return delegate.save(experiment);
        }

        @Override
        public Optional<Experiment> findById(UUID id) {
            return delegate.findById(id);
        }

        @Override
        public List<Experiment> findByProjectId(UUID projectId) {
            return delegate.findByProjectId(projectId);
        }

        @Override
        public List<Experiment> findBySessionId(UUID sessionId) {
            return delegate.findBySessionId(sessionId);
        }

        @Override
        public boolean hasRunningExperiment(UUID sessionId) {
            return delegate.hasRunningExperiment(sessionId);
        }
    }

    private record Fixture(UUID owner,
                           Project project,
                           Session session,
                           InMemoryProjectRepository projects,
                           InMemorySessionRepository sessions,
                           ExperimentRepository experiments,
                           InMemorySnapshotRepository snapshots,
                           InMemorySessionRunLease leases) {
        private Snapshot snapshot(String fingerprint) {
            return new Snapshot(UUID.randomUUID(), project.id(), fingerprint,
                    project.canonicalPath().resolve(fingerprint), Instant.now(), List.of(), List.of());
        }

        private ExperimentApplicationService service(SnapshotPort snapshotPort, WorkspacePort workspaces) {
            return new ExperimentApplicationService(projects, sessions, experiments, snapshots,
                    snapshotPort, workspaces, Instant::now, leases, new com.offcanon.infrastructure.memory.InMemoryPromotionLock());
        }
    }
}
