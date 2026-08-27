package com.pico.agent.application;

import com.pico.agent.domain.AgentRunResult;
import com.pico.experiment.domain.Experiment;
import com.pico.experiment.domain.ExperimentStatus;
import com.pico.infrastructure.memory.InMemoryEventSink;
import com.pico.infrastructure.memory.InMemoryExperimentRepository;
import com.pico.infrastructure.memory.InMemoryProjectRepository;
import com.pico.infrastructure.memory.InMemorySessionRunLease;
import com.pico.infrastructure.memory.InMemorySnapshotRepository;
import com.pico.port.AgentLoopPort;
import com.pico.port.ExperimentRepository;
import com.pico.shared.domain.DomainException;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentCancellationRaceTest {
    @TempDir
    Path temp;

    @Test
    void cancellationRetriesWhenWorkerWinsTheOptimisticLockRace() throws Exception {
        CoordinatedRepository experiments = new CoordinatedRepository();
        Experiment experiment = readyExperiment(experiments);
        CountDownLatch loopEntered = new CountDownLatch(1);
        CountDownLatch allowLoopReturn = new CountDownLatch(1);
        AgentLoopPort loop = (current, cancellation) -> {
            loopEntered.countDown();
            awaitIgnoringInterrupts(allowLoopReturn);
            return new AgentRunResult("finished concurrently", 1, "MODEL_FINISH", List.of());
        };
        ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
        ExecutorService requestExecutor = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "cancel-request"));
        AgentApplicationService service = new AgentApplicationService(experiments,
                new InMemoryProjectRepository(), new InMemorySnapshotRepository(), null,
                loop, null, workerExecutor, new InMemoryEventSink(),
                new InMemorySessionRunLease(), null);

        try {
            service.start(experiment.id());
            assertTrue(loopEntered.await(5, TimeUnit.SECONDS));
            experiments.coordinateCancellation();

            Future<Experiment> cancellation = requestExecutor.submit(() -> service.cancel(experiment.id()));
            assertTrue(experiments.cancelSaveBlocked.await(5, TimeUnit.SECONDS));
            allowLoopReturn.countDown();
            assertTrue(experiments.agentCompletedSaved.await(5, TimeUnit.SECONDS));
            experiments.releaseCancelSave.countDown();

            assertEquals(ExperimentStatus.CANCELLED, cancellation.get(5, TimeUnit.SECONDS).status());
            assertEquals(ExperimentStatus.CANCELLED,
                    experiments.findById(experiment.id()).orElseThrow().status());
        } finally {
            experiments.releaseCancelSave.countDown();
            allowLoopReturn.countDown();
            requestExecutor.shutdownNow();
            workerExecutor.shutdownNow();
        }
    }

    @Test
    void cancellationBeforeRunControlRegistrationPreventsAgentSideEffects() throws Exception {
        StartWindowRepository experiments = new StartWindowRepository();
        Experiment experiment = readyExperiment(experiments);
        AtomicInteger loopInvocations = new AtomicInteger();
        AgentLoopPort loop = (current, cancellation) -> {
            loopInvocations.incrementAndGet();
            return new AgentRunResult("must not run", 1, "MODEL_FINISH", List.of());
        };
        ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
        ExecutorService requestExecutor = Executors.newSingleThreadExecutor();
        AgentApplicationService service = new AgentApplicationService(experiments,
                new InMemoryProjectRepository(), new InMemorySnapshotRepository(), null,
                loop, null, workerExecutor, new InMemoryEventSink(),
                new InMemorySessionRunLease(), null);

        try {
            Future<Experiment> start = requestExecutor.submit(() -> service.start(experiment.id()));
            assertTrue(experiments.runningPersisted.await(5, TimeUnit.SECONDS));

            assertEquals(ExperimentStatus.CANCELLED, service.cancel(experiment.id()).status());
            experiments.releaseStart.countDown();
            start.get(5, TimeUnit.SECONDS);
            workerExecutor.shutdown();
            assertTrue(workerExecutor.awaitTermination(5, TimeUnit.SECONDS));

            assertEquals(0, loopInvocations.get());
            assertEquals(ExperimentStatus.CANCELLED,
                    experiments.findById(experiment.id()).orElseThrow().status());
        } finally {
            experiments.releaseStart.countDown();
            requestExecutor.shutdownNow();
            workerExecutor.shutdownNow();
        }
    }

    @Test
    void workerReloadsPersistedStateBeforeSettlingAnOptimisticLockFailure() throws Exception {
        ConflictOnceRepository experiments = new ConflictOnceRepository();
        Experiment experiment = readyExperiment(experiments);
        AgentLoopPort loop = (current, cancellation) ->
                new AgentRunResult("done", 1, "MODEL_FINISH", List.of());
        ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
        AgentApplicationService service = new AgentApplicationService(experiments,
                new InMemoryProjectRepository(), new InMemorySnapshotRepository(), null,
                loop, null, workerExecutor, new InMemoryEventSink(),
                new InMemorySessionRunLease(), null);

        try {
            service.start(experiment.id());
            Experiment failed = waitFor(experiments, experiment.id(), ExperimentStatus.FAILED);

            assertTrue(failed.failureReason().contains("EXPERIMENT_VERSION_CONFLICT"));
        } finally {
            workerExecutor.shutdownNow();
        }
    }

    @Test
    void persistentSessionStateBlocksASecondRunEvenIfTheLeaseBackendLostItsKey() throws Exception {
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        UUID projectId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Experiment first = readyExperiment(experiments, projectId, sessionId);
        Experiment second = readyExperiment(experiments, projectId, sessionId);
        CountDownLatch keepFirstRunning = new CountDownLatch(1);
        AgentLoopPort loop = (current, cancellation) -> {
            awaitIgnoringInterrupts(keepFirstRunning);
            return new AgentRunResult("done", 1, "MODEL_FINISH", List.of());
        };
        ExecutorService workerExecutor = Executors.newFixedThreadPool(2);
        com.pico.port.SessionRunLeasePort permissiveLease = new com.pico.port.SessionRunLeasePort() {
            @Override public boolean tryAcquire(UUID ignoredSession, UUID ignoredExperiment) { return true; }
            @Override public void release(UUID ignoredSession, UUID ignoredExperiment) { }
        };
        AgentApplicationService service = new AgentApplicationService(experiments,
                new InMemoryProjectRepository(), new InMemorySnapshotRepository(), null,
                loop, null, workerExecutor, new InMemoryEventSink(), permissiveLease, null);

        try {
            service.start(first.id());
            DomainException error = assertThrows(DomainException.class, () -> service.start(second.id()));

            assertEquals("SESSION_ALREADY_RUNNING", error.code());
            assertEquals(ExperimentStatus.READY_TO_RUN, experiments.findById(second.id()).orElseThrow().status());
        } finally {
            keepFirstRunning.countDown();
            workerExecutor.shutdownNow();
        }
    }

    private Experiment readyExperiment(ExperimentRepository repository) {
        return readyExperiment(repository, UUID.randomUUID(), UUID.randomUUID());
    }

    private Experiment readyExperiment(ExperimentRepository repository, UUID projectId, UUID sessionId) {
        Experiment experiment = Experiment.create(projectId, sessionId, "task", Instant.now());
        repository.save(experiment);
        experiment.beginSnapshot();
        repository.save(experiment);
        experiment.attachBase(UUID.randomUUID(), temp.resolve("workspace"));
        repository.save(experiment);
        return experiment;
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private Experiment waitFor(ExperimentRepository repository,
                               UUID experimentId,
                               ExperimentStatus expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Experiment current = repository.findById(experimentId).orElseThrow();
            if (current.status() == expected) return current;
            Thread.sleep(20);
        }
        throw new AssertionError("experiment did not reach " + expected);
    }

    private static final class CoordinatedRepository implements ExperimentRepository {
        private final InMemoryExperimentRepository delegate = new InMemoryExperimentRepository();
        private final AtomicBoolean blockCancellation = new AtomicBoolean(false);
        private final CountDownLatch cancelSaveBlocked = new CountDownLatch(1);
        private final CountDownLatch releaseCancelSave = new CountDownLatch(1);
        private final CountDownLatch agentCompletedSaved = new CountDownLatch(1);

        void coordinateCancellation() {
            blockCancellation.set(true);
        }

        @Override
        public Experiment save(Experiment experiment) {
            if (experiment.status() == ExperimentStatus.CANCELLED
                    && Thread.currentThread().getName().equals("cancel-request")
                    && blockCancellation.compareAndSet(true, false)) {
                cancelSaveBlocked.countDown();
                awaitIgnoringInterrupts(releaseCancelSave);
            }
            Experiment saved = delegate.save(experiment);
            if (experiment.status() == ExperimentStatus.AGENT_COMPLETED) {
                agentCompletedSaved.countDown();
            }
            return saved;
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

    private static final class ConflictOnceRepository implements ExperimentRepository {
        private final InMemoryExperimentRepository delegate = new InMemoryExperimentRepository();
        private final AtomicBoolean rejectAgentCompletion = new AtomicBoolean(true);

        @Override
        public Experiment save(Experiment experiment) {
            if (experiment.status() == ExperimentStatus.AGENT_COMPLETED
                    && rejectAgentCompletion.compareAndSet(true, false)) {
                throw new DomainException("EXPERIMENT_VERSION_CONFLICT", "injected conflict");
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

    private static final class StartWindowRepository implements ExperimentRepository {
        private final InMemoryExperimentRepository delegate = new InMemoryExperimentRepository();
        private final AtomicBoolean blockStart = new AtomicBoolean(true);
        private final CountDownLatch runningPersisted = new CountDownLatch(1);
        private final CountDownLatch releaseStart = new CountDownLatch(1);

        @Override
        public Experiment save(Experiment experiment) {
            Experiment saved = delegate.save(experiment);
            if (experiment.status() == ExperimentStatus.RUNNING && blockStart.compareAndSet(true, false)) {
                runningPersisted.countDown();
                awaitIgnoringInterrupts(releaseStart);
            }
            return saved;
        }

        @Override public Optional<Experiment> findById(UUID id) { return delegate.findById(id); }
        @Override public List<Experiment> findByProjectId(UUID id) { return delegate.findByProjectId(id); }
        @Override public List<Experiment> findBySessionId(UUID id) { return delegate.findBySessionId(id); }
        @Override public boolean hasRunningExperiment(UUID id) { return delegate.hasRunningExperiment(id); }
    }
}
