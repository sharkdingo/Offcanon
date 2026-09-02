package com.offcanon.application;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.infrastructure.git.GitSnapshotAdapter;
import com.offcanon.infrastructure.memory.InMemoryExperimentRepository;
import com.offcanon.infrastructure.memory.InMemoryProjectRepository;
import com.offcanon.infrastructure.memory.InMemorySessionRepository;
import com.offcanon.infrastructure.memory.InMemorySessionRunLease;
import com.offcanon.infrastructure.memory.InMemorySnapshotRepository;
import com.offcanon.infrastructure.process.ProcessRunner;
import com.offcanon.infrastructure.workspace.LocalWorkspaceAdapter;
import com.offcanon.project.domain.Project;
import com.offcanon.verification.domain.VerificationResult;
import com.offcanon.workspace.domain.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentContinuationApplicationServiceTest {
    @TempDir
    Path temp;

    @Test
    void sealedResultCanContinueWithoutAcceptanceCommands() throws Exception {
        Fixture fixture = fixture("waiting");
        Experiment source = fixture.create("implement parser");
        Snapshot result = sealWaiting(fixture, source, "candidate awaiting verification\n");

        Experiment successor = fixture.service.continueExperiment(fixture.owner, source.id(), "Keep going.");

        assertEquals(source.id(), successor.continuedFromExperimentId());
        assertEquals(ExperimentStatus.READY_TO_RUN, successor.status());
        assertEquals("candidate awaiting verification\n",
                Files.readString(successor.workspacePath().resolve("app.txt")));
        assertEquals("canonical\n", Files.readString(fixture.canonical.resolve("app.txt")));
        Experiment storedSource = fixture.experiments.findById(source.id()).orElseThrow();
        assertEquals(ExperimentStatus.AGENT_COMPLETED, storedSource.status());
        assertEquals(result.id(), storedSource.resultSnapshotId());
        assertFalse(fixture.experiments.hasRunningExperiment(source.sessionId(), successor.id()));
    }

    @Test
    void sealedResultContinuationUsesSnapshotAfterSourceWorkspaceContentIsRemoved() throws Exception {
        Fixture fixture = fixture("waiting-retained");
        Experiment source = fixture.create("implement parser");
        sealWaiting(fixture, source, "durable sealed candidate\n");
        Files.delete(source.workspacePath().resolve("app.txt"));

        Experiment successor = fixture.service.continueExperiment(fixture.owner, source.id(), "Keep going.");

        assertEquals("durable sealed candidate\n",
                Files.readString(successor.workspacePath().resolve("app.txt")));
        assertEquals("canonical\n", Files.readString(fixture.canonical.resolve("app.txt")));
    }

    @Test
    void sealedWaitingResultCannotBypassConfiguredAcceptanceCommands() throws Exception {
        Fixture fixture = fixture("waiting-with-policy", List.of("test"));
        Experiment source = fixture.create("implement parser");
        sealWaiting(fixture, source, "candidate awaiting verification\n");

        com.offcanon.shared.domain.DomainException error = assertThrows(
                com.offcanon.shared.domain.DomainException.class,
                () -> fixture.service.continueExperiment(fixture.owner, source.id(), "Keep going."));

        assertEquals("EXPERIMENT_NOT_CONTINUABLE", error.code());
        assertEquals("canonical\n", Files.readString(fixture.canonical.resolve("app.txt")));
    }

    @Test
    void unsealedAgentCompletedResultCannotContinue() throws Exception {
        Fixture fixture = fixture("unsealed");
        Experiment source = fixture.create("implement parser");
        start(fixture, source);
        source.markAgentCompleted("finished editing");
        fixture.experiments.save(source);

        com.offcanon.shared.domain.DomainException error = assertThrows(
                com.offcanon.shared.domain.DomainException.class,
                () -> fixture.service.continueExperiment(fixture.owner, source.id(), "Keep going."));

        assertEquals("EXPERIMENT_NOT_CONTINUABLE", error.code());
    }

    @Test
    void sealedResultCannotContinueUntilTheOriginalRunReleasesItsLease() throws Exception {
        Fixture fixture = fixture("waiting-with-lease");
        Experiment source = fixture.create("implement parser");
        sealWaiting(fixture, source, "candidate awaiting verification\n");
        var heldLease = fixture.leases.tryAcquire(source.sessionId(), source.id()).orElseThrow();
        try {
            com.offcanon.shared.domain.DomainException error = assertThrows(
                    com.offcanon.shared.domain.DomainException.class,
                    () -> fixture.service.continueExperiment(fixture.owner, source.id(), "Keep going."));

            assertEquals("SESSION_ALREADY_RUNNING", error.code());
        } finally {
            heldLease.release();
        }
    }

    @Test
    void anotherPreparedExperimentBlocksContinuationInTheSameSession() throws Exception {
        Fixture fixture = fixture("waiting-with-queued-successor");
        Experiment source = fixture.create("implement parser");
        sealWaiting(fixture, source, "candidate awaiting verification\n");
        Experiment queued = Experiment.continueFrom(fixture.project.id(), source.sessionId(), source.id(),
                "already queued", Instant.now());
        fixture.experiments.save(queued);
        queued.beginSnapshot();
        fixture.experiments.save(queued);
        queued.attachBase(source.baseSnapshotId(), source.workspacePath());
        fixture.experiments.save(queued);

        com.offcanon.shared.domain.DomainException error = assertThrows(
                com.offcanon.shared.domain.DomainException.class,
                () -> fixture.service.continueExperiment(fixture.owner, source.id(), "Keep going."));

        assertEquals("SESSION_ALREADY_RUNNING", error.code());
    }

    @Test
    void providerFailureCarriesPartialWorkWhenCanonicalStillMatches() throws Exception {
        Fixture fixture = fixture("provider");
        Experiment source = fixture.create("implement parser");
        start(fixture, source);
        Files.writeString(source.workspacePath().resolve("partial.txt"), "valuable draft\n");
        source.fail("MODEL_TRANSIENT_FAILURE: HTTP 429");
        fixture.experiments.save(source);

        Experiment successor = fixture.service.continueExperiment(fixture.owner, source.id(), "Please continue.");

        assertEquals(source.id(), successor.continuedFromExperimentId());
        assertEquals(source.sessionId(), successor.sessionId());
        assertEquals(ExperimentStatus.READY_TO_RUN, successor.status());
        assertEquals("valuable draft\n", Files.readString(successor.workspacePath().resolve("partial.txt")));
        assertTrue(git(fixture.runner, successor.workspacePath(), "status", "--short").stdout()
                .contains("partial.txt"));
        assertFalse(Files.exists(fixture.canonical.resolve("partial.txt")));
    }

    @Test
    void verificationFailureCarriesSealedResultAsVisibleDraft() throws Exception {
        Fixture fixture = fixture("verification");
        Experiment source = fixture.create("fix validation");
        Snapshot result = seal(fixture, source, "candidate that still fails tests\n");
        source.markVerified(VerificationResult.failed(List.of(), "mvn test failed"));
        fixture.experiments.save(source);

        Experiment successor = fixture.service.continueExperiment(fixture.owner, source.id(), "Fix the failing test.");

        assertEquals(result.id(), source.resultSnapshotId());
        assertEquals("candidate that still fails tests\n",
                Files.readString(successor.workspacePath().resolve("app.txt")));
        assertTrue(git(fixture.runner, successor.workspacePath(), "diff", "--", "app.txt").stdout()
                .contains("candidate that still fails tests"));
        assertEquals("canonical\n", Files.readString(fixture.canonical.resolve("app.txt")));
    }

    @Test
    void staleContinuationUsesCurrentCanonicalAndDoesNotCarryOldFilesystemState() throws Exception {
        Fixture fixture = fixture("stale");
        Experiment source = fixture.create("change stale behavior");
        seal(fixture, source, "old experiment result\n");
        source.markVerified(VerificationResult.passed(List.of()));
        fixture.experiments.save(source);
        Files.writeString(fixture.canonical.resolve("app.txt"), "new canonical fact\n");
        source.markStale("Canonical changed");
        fixture.experiments.save(source);

        Experiment successor = fixture.service.continueExperiment(fixture.owner, source.id(), "Apply the goal to current code.");

        assertEquals("new canonical fact\n", Files.readString(successor.workspacePath().resolve("app.txt")));
        assertTrue(git(fixture.runner, successor.workspacePath(), "status", "--short").stdout().isBlank());
        assertEquals(fixture.snapshots.findById(successor.baseSnapshotId()).orElseThrow().fingerprint(),
                fixture.snapshotPort.currentFingerprint(fixture.project));
    }

    @Test
    void promotedContinuationStartsFromAppliedCanonical() throws Exception {
        Fixture fixture = fixture("promoted");
        Experiment source = fixture.create("apply feature");
        seal(fixture, source, "applied result\n");
        source.markVerified(VerificationResult.passed(List.of()));
        fixture.experiments.save(source);
        Files.writeString(fixture.canonical.resolve("app.txt"), "applied result\n");
        source.beginPromotion();
        fixture.experiments.save(source);
        source.markPromoting();
        fixture.experiments.save(source);
        source.markPromoted();
        fixture.experiments.save(source);

        Experiment successor = fixture.service.continueExperiment(fixture.owner, source.id(), "Add an edge case.");

        assertEquals("applied result\n", Files.readString(successor.workspacePath().resolve("app.txt")));
        assertTrue(git(fixture.runner, successor.workspacePath(), "status", "--short").stdout().isBlank());
        assertEquals(source.id(), successor.continuedFromExperimentId());
    }

    private Snapshot seal(Fixture fixture, Experiment experiment, String content) throws Exception {
        Snapshot result = sealWaiting(fixture, experiment, content);
        experiment.beginVerification();
        fixture.experiments.save(experiment);
        return result;
    }

    private Snapshot sealWaiting(Fixture fixture, Experiment experiment, String content) throws Exception {
        start(fixture, experiment);
        Files.writeString(experiment.workspacePath().resolve("app.txt"), content);
        experiment.markAgentCompleted("implemented the requested change");
        fixture.experiments.save(experiment);
        Snapshot base = fixture.snapshots.findById(experiment.baseSnapshotId()).orElseThrow();
        Snapshot result = fixture.snapshotPort.captureWorkspace(fixture.project, experiment.workspacePath(), base.fingerprint());
        fixture.snapshots.save(result);
        experiment.sealResult(result.id());
        fixture.experiments.save(experiment);
        return result;
    }

    private void start(Fixture fixture, Experiment experiment) {
        experiment.start();
        fixture.experiments.save(experiment);
    }

    private Fixture fixture(String name) throws Exception {
        return fixture(name, List.of());
    }

    private Fixture fixture(String name, List<String> verificationCommands) throws Exception {
        Path canonical = Files.createDirectories(temp.resolve(name).resolve("canonical"));
        ProcessRunner runner = new ProcessRunner();
        git(runner, canonical, "-c", "init.defaultBranch=main", "init", "-q");
        Files.writeString(canonical.resolve("app.txt"), "canonical\n");
        git(runner, canonical, "add", "app.txt");
        git(runner, canonical, "-c", "user.name=Test", "-c", "user.email=test@localhost",
                "commit", "-q", "-m", "base");
        Path runtime = temp.resolve(name).resolve("runtime");
        UUID owner = UUID.randomUUID();
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        Project project = projects.save(Project.create(owner, name, canonical, verificationCommands, Instant.now()));
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        InMemorySnapshotRepository snapshots = new InMemorySnapshotRepository();
        GitSnapshotAdapter snapshotPort = new GitSnapshotAdapter(runner, runtime.toString());
        LocalWorkspaceAdapter workspaces = new LocalWorkspaceAdapter(runtime.toString(), runner);
        AtomicLong seconds = new AtomicLong();
        InMemorySessionRunLease leases = new InMemorySessionRunLease();
        ExperimentApplicationService service = new ExperimentApplicationService(projects, sessions, experiments,
                snapshots, snapshotPort, workspaces,
                () -> Instant.parse("2026-08-28T00:00:00Z").plusSeconds(seconds.getAndIncrement()),
                leases, new com.offcanon.infrastructure.memory.InMemoryPromotionLock());
        return new Fixture(owner, canonical, project, experiments, snapshots, snapshotPort, leases, runner, service);
    }

    private ProcessRunner.ProcessResult git(ProcessRunner runner, Path cwd, String... arguments) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(List.of("git", "-C", cwd.toString()));
        command.addAll(List.of(arguments));
        ProcessRunner.ProcessResult result = runner.run(command, cwd, Map.of(), Duration.ofSeconds(20));
        assertEquals(0, result.exitCode(), result.stderr());
        return result;
    }

    private record Fixture(UUID owner,
                           Path canonical,
                           Project project,
                           InMemoryExperimentRepository experiments,
                           InMemorySnapshotRepository snapshots,
                           GitSnapshotAdapter snapshotPort,
                           InMemorySessionRunLease leases,
                           ProcessRunner runner,
                           ExperimentApplicationService service) {
        private Experiment create(String task) {
            return service.create(owner, project.id(), null, "Coding task", task);
        }
    }
}
