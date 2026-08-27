package com.offcanon.agent.application;

import com.offcanon.agent.domain.AgentRunResult;
import com.offcanon.agent.domain.SessionContext;
import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.infrastructure.memory.InMemoryEventSink;
import com.offcanon.infrastructure.memory.InMemoryExperimentRepository;
import com.offcanon.infrastructure.memory.InMemoryProjectRepository;
import com.offcanon.infrastructure.memory.InMemorySessionRunLease;
import com.offcanon.port.AgentLoopPort;
import com.offcanon.port.CancellationPort;
import com.offcanon.project.domain.Project;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.verification.domain.VerificationResult;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionContextIntegrationTest {
    @TempDir
    Path temp;

    @Test
    void nextExperimentReceivesPriorIntentAndSummaryWithoutPriorObservations() throws Exception {
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        UUID sessionId = UUID.randomUUID();
        Project project = projects.save(Project.create("demo", temp, List.of("mvn test"), Instant.now()));
        Experiment previous = completedExperiment(experiments, project.id(), sessionId,
                "keep compatibility", "changed one adapter and verified it", Instant.parse("2026-08-27T10:00:00Z"));
        Experiment current = Experiment.create(project.id(), sessionId, "add cancellation", Instant.parse("2026-08-27T10:01:00Z"));
        experiments.save(current);
        current.beginSnapshot();
        experiments.save(current);
        current.attachBase(UUID.randomUUID(), temp.resolve("current"));
        experiments.save(current);

        AtomicReference<Optional<SessionContext>> captured = new AtomicReference<>(Optional.empty());
        CountDownLatch invoked = new CountDownLatch(1);
        AgentLoopPort loop = new AgentLoopPort() {
            @Override
            public AgentRunResult run(Experiment experiment, CancellationPort cancellation) {
                throw new AssertionError("provenance-aware overload was not used");
            }

            @Override
            public AgentRunResult run(Experiment experiment,
                                      CancellationPort cancellation,
                                      Optional<SessionContext> sessionContext) {
                captured.set(sessionContext);
                invoked.countDown();
                throw new DomainException("EXPECTED_STOP", "stop after capturing context");
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AgentApplicationService service = new AgentApplicationService(experiments, projects,
                null, null, loop, null, executor, new InMemoryEventSink(),
                new InMemorySessionRunLease(), null);
        try {
            service.start(current.id());
            assertTrue(invoked.await(5, TimeUnit.SECONDS));
            waitFor(experiments, current.id(), ExperimentStatus.FAILED);

            SessionContext context = captured.get().orElseThrow();
            assertEquals(previous.id(), context.priorExperimentId());
            assertEquals(previous.baseSnapshotId(), context.priorSnapshotId());
            assertEquals("keep compatibility", context.priorTask());
            assertEquals("changed one adapter and verified it", context.priorSummary());
        } finally {
            executor.shutdownNow();
        }
    }

    private Experiment completedExperiment(InMemoryExperimentRepository repository,
                                           UUID projectId,
                                           UUID sessionId,
                                           String task,
                                           String summary,
                                           Instant createdAt) {
        Experiment experiment = Experiment.create(projectId, sessionId, task, createdAt);
        repository.save(experiment);
        experiment.beginSnapshot();
        repository.save(experiment);
        experiment.attachBase(UUID.randomUUID(), temp.resolve("previous"));
        repository.save(experiment);
        experiment.start();
        repository.save(experiment);
        experiment.markAgentCompleted(summary);
        repository.save(experiment);
        experiment.sealResult(UUID.randomUUID());
        repository.save(experiment);
        experiment.beginVerification();
        repository.save(experiment);
        experiment.markVerified(VerificationResult.passed(List.of()));
        repository.save(experiment);
        return experiment;
    }

    private void waitFor(InMemoryExperimentRepository repository,
                         UUID experimentId,
                         ExperimentStatus expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (repository.findById(experimentId).orElseThrow().status() == expected) return;
            Thread.sleep(20);
        }
        throw new AssertionError("experiment did not reach " + expected);
    }
}
