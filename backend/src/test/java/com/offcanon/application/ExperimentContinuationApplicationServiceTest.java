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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentContinuationApplicationServiceTest {
    @TempDir
    Path temp;

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
        start(fixture, experiment);
        Files.writeString(experiment.workspacePath().resolve("app.txt"), content);
        experiment.markAgentCompleted("implemented the requested change");
        fixture.experiments.save(experiment);
        Snapshot base = fixture.snapshots.findById(experiment.baseSnapshotId()).orElseThrow();
        Snapshot result = fixture.snapshotPort.captureWorkspace(fixture.project, experiment.workspacePath(), base.fingerprint());
        fixture.snapshots.save(result);
        experiment.sealResult(result.id());
        fixture.experiments.save(experiment);
        experiment.beginVerification();
        fixture.experiments.save(experiment);
        return result;
    }

    private void start(Fixture fixture, Experiment experiment) {
        experiment.start();
        fixture.experiments.save(experiment);
    }

    private Fixture fixture(String name) throws Exception {
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
        Project project = projects.save(Project.create(owner, name, canonical, List.of(), Instant.now()));
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        InMemorySnapshotRepository snapshots = new InMemorySnapshotRepository();
        GitSnapshotAdapter snapshotPort = new GitSnapshotAdapter(runner, runtime.toString());
        LocalWorkspaceAdapter workspaces = new LocalWorkspaceAdapter(runtime.toString(), runner);
        AtomicLong seconds = new AtomicLong();
        ExperimentApplicationService service = new ExperimentApplicationService(projects, sessions, experiments,
                snapshots, snapshotPort, workspaces,
                () -> Instant.parse("2026-08-28T00:00:00Z").plusSeconds(seconds.getAndIncrement()),
                new InMemorySessionRunLease(), new com.offcanon.infrastructure.memory.InMemoryPromotionLock());
        return new Fixture(owner, canonical, project, experiments, snapshots, snapshotPort, runner, service);
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
                           ProcessRunner runner,
                           ExperimentApplicationService service) {
        private Experiment create(String task) {
            return service.create(owner, project.id(), null, "Coding task", task);
        }
    }
}
