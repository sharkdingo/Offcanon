package com.offcanon.agent.application;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.infrastructure.memory.InMemoryEventSink;
import com.offcanon.infrastructure.memory.InMemoryExperimentRepository;
import com.offcanon.infrastructure.memory.InMemoryProjectRepository;
import com.offcanon.infrastructure.memory.InMemorySessionRunLease;
import com.offcanon.project.domain.Project;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRecoveryServiceTest {
    @Test
    void settlesInterruptedSnapshotInitializationOnStartup() {
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        Project project = projects.save(Project.create(UUID.randomUUID(), "demo", Path.of("/tmp/offcanon-demo"),
                List.of("test"), Instant.now()));

        Experiment created = Experiment.create(project.id(), UUID.randomUUID(), "created task", Instant.now());
        experiments.save(created);
        Experiment snapshotting = Experiment.create(project.id(), UUID.randomUUID(), "snapshot task", Instant.now());
        experiments.save(snapshotting);
        snapshotting.beginSnapshot();
        experiments.save(snapshotting);

        AgentRecoveryService recovery = new AgentRecoveryService(projects, experiments,
                new InMemorySessionRunLease(), new InMemoryEventSink());

        assertEquals(2, recovery.recoverInterruptedInitialization());
        assertEquals(ExperimentStatus.FAILED, experiments.findById(created.id()).orElseThrow().status());
        assertEquals(ExperimentStatus.FAILED, experiments.findById(snapshotting.id()).orElseThrow().status());
    }

    @Test
    void settlesPersistedInterruptibleRunsAfterLeaseHasExpired() {
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        InMemorySessionRunLease leases = new InMemorySessionRunLease();
        Project project = projects.save(Project.create(UUID.randomUUID(), "demo", Path.of("/tmp/offcanon-demo"),
                List.of("test"), Instant.now()));

        Experiment running = ready(experiments, project.id());
        running.start();
        experiments.save(running);
        Experiment completed = ready(experiments, project.id());
        completed.start();
        experiments.save(completed);
        completed.markAgentCompleted("draft");
        experiments.save(completed);

        AgentRecoveryService recovery = new AgentRecoveryService(projects, experiments, leases, new InMemoryEventSink());

        assertEquals(2, recovery.recoverInterruptedRuns());
        assertEquals(ExperimentStatus.FAILED, experiments.findById(running.id()).orElseThrow().status());
        assertEquals(ExperimentStatus.FAILED, experiments.findById(completed.id()).orElseThrow().status());
        assertTrue(experiments.findById(running.id()).orElseThrow().failureReason().contains("application restart"));
    }

    @Test
    void preservesASealedAgentCompletedResultForExplicitVerification() {
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        Project project = projects.save(Project.create(UUID.randomUUID(), "demo", Path.of("/tmp/offcanon-demo"),
                List.of("test"), Instant.now()));
        Experiment sealed = ready(experiments, project.id());
        sealed.start();
        experiments.save(sealed);
        sealed.markAgentCompleted("draft");
        experiments.save(sealed);
        sealed.sealResult(UUID.randomUUID());
        experiments.save(sealed);

        AgentRecoveryService recovery = new AgentRecoveryService(projects, experiments,
                new InMemorySessionRunLease(), new InMemoryEventSink());

        assertEquals(0, recovery.recoverInterruptedRuns());
        assertEquals(ExperimentStatus.AGENT_COMPLETED,
                experiments.findById(sealed.id()).orElseThrow().status());
    }

    @Test
    void returnsInterruptedVerificationToTheDurableWaitingState() {
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        Project project = projects.save(Project.create(UUID.randomUUID(), "demo", Path.of("/tmp/offcanon-demo"),
                List.of("test"), Instant.now()));
        Experiment verifying = ready(experiments, project.id());
        verifying.start();
        experiments.save(verifying);
        verifying.markAgentCompleted("sealed draft");
        experiments.save(verifying);
        UUID resultSnapshotId = UUID.randomUUID();
        verifying.sealResult(resultSnapshotId);
        experiments.save(verifying);
        verifying.beginVerification();
        experiments.save(verifying);

        AgentRecoveryService recovery = new AgentRecoveryService(projects, experiments,
                new InMemorySessionRunLease(), new InMemoryEventSink());

        assertEquals(1, recovery.recoverInterruptedRuns());
        Experiment recovered = experiments.findById(verifying.id()).orElseThrow();
        assertEquals(ExperimentStatus.AGENT_COMPLETED, recovered.status());
        assertEquals(resultSnapshotId, recovered.resultSnapshotId());
        assertTrue(recovered.failureReason().contains("run verification again"));
        assertEquals(0, recovery.recoverInterruptedRuns());
    }

    @Test
    void activeLeaseDefersRecoveryUntilTheWorkerLeaseIsReleased() {
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        InMemorySessionRunLease leases = new InMemorySessionRunLease();
        Project project = projects.save(Project.create(UUID.randomUUID(), "demo", Path.of("/tmp/offcanon-demo"),
                List.of("test"), Instant.now()));
        Experiment running = ready(experiments, project.id());
        running.start();
        experiments.save(running);
        var workerLease = leases.tryAcquire(running.sessionId(), running.id()).orElseThrow();

        AgentRecoveryService recovery = new AgentRecoveryService(projects, experiments, leases, new InMemoryEventSink());

        assertEquals(0, recovery.recoverInterruptedRuns());
        assertEquals(ExperimentStatus.RUNNING, experiments.findById(running.id()).orElseThrow().status());
        workerLease.release();
        assertEquals(1, recovery.recoverInterruptedRuns());
        assertEquals(ExperimentStatus.FAILED, experiments.findById(running.id()).orElseThrow().status());
    }

    @Test
    void scheduledRecoveryRetriesInitializationAfterTheCreatorLeaseExpires() {
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        InMemorySessionRunLease leases = new InMemorySessionRunLease();
        Project project = projects.save(Project.create(UUID.randomUUID(), "demo", Path.of("/tmp/offcanon-demo"),
                List.of("test"), Instant.now()));
        Experiment snapshotting = Experiment.create(project.id(), UUID.randomUUID(), "snapshot task", Instant.now());
        experiments.save(snapshotting);
        snapshotting.beginSnapshot();
        experiments.save(snapshotting);
        var creatorLease = leases.tryAcquire(snapshotting.sessionId(), snapshotting.id()).orElseThrow();

        AgentRecoveryService recovery = new AgentRecoveryService(projects, experiments, leases, new InMemoryEventSink());
        recovery.recoverOnStartup();
        assertEquals(ExperimentStatus.SNAPSHOTTING,
                experiments.findById(snapshotting.id()).orElseThrow().status());

        creatorLease.release();
        recovery.recoverExpiredRuns();
        assertEquals(ExperimentStatus.FAILED,
                experiments.findById(snapshotting.id()).orElseThrow().status());
    }

    private Experiment ready(InMemoryExperimentRepository experiments, UUID projectId) {
        Experiment experiment = Experiment.create(projectId, UUID.randomUUID(), "task", Instant.now());
        experiments.save(experiment);
        experiment.beginSnapshot();
        experiments.save(experiment);
        experiment.attachBase(UUID.randomUUID(), Path.of("/tmp/offcanon-workspace-" + experiment.id()));
        experiments.save(experiment);
        return experiment;
    }
}
