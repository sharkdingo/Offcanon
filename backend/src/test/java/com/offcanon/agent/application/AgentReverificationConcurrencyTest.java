package com.offcanon.agent.application;

import com.offcanon.agent.domain.AgentRunResult;
import com.offcanon.application.ProjectApplicationService;
import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.infrastructure.memory.InMemoryEventSink;
import com.offcanon.infrastructure.memory.InMemoryExperimentRepository;
import com.offcanon.infrastructure.memory.InMemoryPromotionLock;
import com.offcanon.infrastructure.memory.InMemoryProjectRepository;
import com.offcanon.infrastructure.memory.InMemorySessionRunLease;
import com.offcanon.infrastructure.memory.InMemorySnapshotRepository;
import com.offcanon.port.AgentLoopPort;
import com.offcanon.port.PromotionLockPort;
import com.offcanon.port.SnapshotPort;
import com.offcanon.port.VerificationPort;
import com.offcanon.port.WorkspacePort;
import com.offcanon.project.domain.Project;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.verification.domain.VerificationResult;
import com.offcanon.workspace.domain.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentReverificationConcurrencyTest {
    @TempDir
    Path temp;

    @Test
    void locksPolicyBeforeRunningCommandsAndUsesTheResolvedPolicy() throws Exception {
        CountDownLatch verificationStarted = new CountDownLatch(1);
        CountDownLatch releaseVerification = new CountDownLatch(1);
        AtomicReference<List<String>> observedCommands = new AtomicReference<>();
        VerificationPort verification = mock(VerificationPort.class);
        doAnswer(invocation -> {
            Project resolved = invocation.getArgument(0);
            observedCommands.set(resolved.verificationCommands());
            verificationStarted.countDown();
            if (!releaseVerification.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("verification was not released");
            }
            return VerificationResult.passed(List.of());
        }).when(verification).verify(any(), any(), any(), any(), any());

        InMemoryPromotionLock promotionLock = new InMemoryPromotionLock();
        Fixture fixture = fixture(List.of("old test"), verification, promotionLock);

        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            var reverification = callers.submit(() -> fixture.agent().reverify(fixture.experiment().id()));
            assertTrue(verificationStarted.await(5, TimeUnit.SECONDS),
                    "verification did not reach the command boundary");
            assertEquals(ExperimentStatus.VERIFYING,
                    fixture.experiments().findById(fixture.experiment().id()).orElseThrow().status());

            var update = callers.submit(() -> updatePolicy(fixture.projectService(), fixture.ownerId(),
                    fixture.project(), List.of("new test")));
            DomainException error = get(update, 2, TimeUnit.SECONDS);
            assertEquals("VERIFICATION_POLICY_LOCKED", error.code());

            releaseVerification.countDown();
            Experiment verified = get(reverification, 5, TimeUnit.SECONDS);
            assertEquals(ExperimentStatus.VERIFIED, verified.status());
            assertEquals(List.of("old test"), observedCommands.get());
            assertEquals(List.of("old test"),
                    fixture.projects().findById(fixture.project().id()).orElseThrow().verificationCommands());
        } finally {
            releaseVerification.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void usesTheNewPolicyWhenTheUpdateWinsTheProjectLock() throws Exception {
        AtomicReference<List<String>> observedCommands = new AtomicReference<>();
        VerificationPort verification = mock(VerificationPort.class);
        doAnswer(invocation -> {
            Project resolved = invocation.getArgument(0);
            observedCommands.set(resolved.verificationCommands());
            return VerificationResult.passed(List.of());
        }).when(verification).verify(any(), any(), any(), any(), any());

        FirstAcquisitionGate promotionLock = new FirstAcquisitionGate();
        Fixture fixture = fixture(List.of("old test"), verification, promotionLock);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            var update = callers.submit(() -> fixture.projectService().update(
                    fixture.ownerId(), fixture.project().id(), fixture.project().name(),
                    fixture.project().canonicalPath().toString(), List.of("new test")));
            assertTrue(promotionLock.awaitFirstLock(5, TimeUnit.SECONDS),
                    "project update did not acquire the shared lock");

            var reverification = callers.submit(() -> fixture.agent().reverify(fixture.experiment().id()));
            assertTrue(promotionLock.awaitSecondAttempt(5, TimeUnit.SECONDS),
                    "reverification did not attempt to acquire the shared lock");

            promotionLock.releaseFirstLock();
            Project updated = get(update, 5, TimeUnit.SECONDS);
            Experiment verified = get(reverification, 5, TimeUnit.SECONDS);

            assertEquals(List.of("new test"), updated.verificationCommands());
            assertEquals(ExperimentStatus.VERIFIED, verified.status());
            assertEquals(List.of("new test"), observedCommands.get());
        } finally {
            promotionLock.releaseFirstLock();
            callers.shutdownNow();
        }
    }

    @Test
    void persistedSuccessorBlocksReverificationAfterAProcessRestart() {
        VerificationPort verification = mock(VerificationPort.class);
        Fixture fixture = fixture(List.of("test"), verification, new InMemoryPromotionLock());

        Experiment successor = Experiment.continueFrom(fixture.project().id(), fixture.experiment().sessionId(),
                fixture.experiment().id(), "next task", Instant.now());
        fixture.experiments().save(successor);
        successor.beginSnapshot();
        fixture.experiments().save(successor);
        successor.attachBase(UUID.randomUUID(), temp);
        fixture.experiments().save(successor);
        successor.start();
        fixture.experiments().save(successor);

        DomainException error = assertThrows(DomainException.class,
                () -> fixture.agent().reverify(fixture.experiment().id()));

        assertEquals("SESSION_ALREADY_RUNNING", error.code());
        assertEquals(ExperimentStatus.RUNNING,
                fixture.experiments().findById(successor.id()).orElseThrow().status());
    }

    private Fixture fixture(List<String> commands,
                            VerificationPort verification,
                            PromotionLockPort promotionLock) {
        Instant now = Instant.now();
        UUID ownerId = UUID.randomUUID();
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        Project project = projects.save(Project.create(ownerId, "demo", temp, commands, now));

        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        Experiment experiment = Experiment.create(project.id(), UUID.randomUUID(), "task", now);
        experiments.save(experiment);
        experiment.beginSnapshot();
        experiments.save(experiment);
        UUID baseId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        experiment.attachBase(baseId, temp);
        experiments.save(experiment);
        experiment.start();
        experiments.save(experiment);
        experiment.markAgentCompleted("done");
        experiments.save(experiment);
        experiment.sealResult(resultId);
        experiments.save(experiment);

        InMemorySnapshotRepository snapshots = new InMemorySnapshotRepository();
        snapshots.save(new Snapshot(baseId, project.id(), "base", temp, now,
                List.of(), List.of()));
        snapshots.save(new Snapshot(resultId, project.id(), "result", temp, now.plusSeconds(1),
                List.of(), List.of()));

        SnapshotPort snapshotPort = mock(SnapshotPort.class);
        when(snapshotPort.resolveProjectRoot(any(Path.class))).thenReturn(temp);
        when(snapshotPort.fingerprintWorkspace(any(), any(), any())).thenReturn("result");
        WorkspacePort workspaces = mock(WorkspacePort.class);
        when(workspaces.createVerificationWorkspace(any(), any())).thenReturn(temp);
        AgentLoopPort loop = (current, cancellation, context, settings) ->
                new AgentRunResult("done", 1, "MODEL_FINISH", List.of());
        AgentApplicationService agent = new AgentApplicationService(
                experiments, projects, snapshots, snapshotPort, loop, verification,
                mock(ExecutorService.class), new InMemoryEventSink(), new InMemorySessionRunLease(),
                workspaces, null, null, null, promotionLock, 20, 600, 80_000);
        ProjectApplicationService projectService = new ProjectApplicationService(
                projects, snapshotPort, experiments, promotionLock);
        return new Fixture(ownerId, project, projects, experiments, experiment, agent, projectService);
    }

    private DomainException updatePolicy(ProjectApplicationService service,
                                         UUID ownerId,
                                         Project project,
                                         List<String> commands) {
        try {
            service.update(ownerId, project.id(), project.name(), project.canonicalPath().toString(), commands);
            fail("policy update should be rejected while verification is active");
            return null;
        } catch (DomainException error) {
            return error;
        }
    }

    private <T> T get(java.util.concurrent.Future<T> future, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(timeout, unit);
    }

    private record Fixture(UUID ownerId,
                           Project project,
                           InMemoryProjectRepository projects,
                           InMemoryExperimentRepository experiments,
                           Experiment experiment,
                           AgentApplicationService agent,
                           ProjectApplicationService projectService) {
    }

    private static final class FirstAcquisitionGate implements PromotionLockPort {
        private final InMemoryPromotionLock delegate = new InMemoryPromotionLock();
        private final AtomicInteger attempts = new AtomicInteger();
        private final CountDownLatch firstLockHeld = new CountDownLatch(1);
        private final CountDownLatch secondAttempted = new CountDownLatch(1);
        private final CountDownLatch releaseFirstLock = new CountDownLatch(1);

        @Override
        public <T> T withProjectLock(UUID projectId, Supplier<T> action) {
            int attempt = attempts.incrementAndGet();
            if (attempt == 2) secondAttempted.countDown();
            return delegate.withProjectLock(projectId, () -> {
                if (attempt == 1) {
                    firstLockHeld.countDown();
                    awaitRelease();
                }
                return action.get();
            });
        }

        @Override
        public void assertHeld(UUID projectId) {
            delegate.assertHeld(projectId);
        }

        private boolean awaitFirstLock(long timeout, TimeUnit unit) throws InterruptedException {
            return firstLockHeld.await(timeout, unit);
        }

        private boolean awaitSecondAttempt(long timeout, TimeUnit unit) throws InterruptedException {
            return secondAttempted.await(timeout, unit);
        }

        private void releaseFirstLock() {
            releaseFirstLock.countDown();
        }

        private void awaitRelease() {
            try {
                if (!releaseFirstLock.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("first project lock was not released");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while holding the first project lock", error);
            }
        }
    }
}
