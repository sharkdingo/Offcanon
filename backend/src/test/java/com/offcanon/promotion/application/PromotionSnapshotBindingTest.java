package com.offcanon.promotion.application;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.infrastructure.git.GitSnapshotAdapter;
import com.offcanon.infrastructure.memory.InMemoryEventSink;
import com.offcanon.infrastructure.memory.InMemoryEvidenceRepository;
import com.offcanon.infrastructure.memory.InMemoryExperimentRepository;
import com.offcanon.infrastructure.memory.InMemoryProjectRepository;
import com.offcanon.infrastructure.memory.InMemoryPromotionLock;
import com.offcanon.infrastructure.memory.InMemoryPromotionJournal;
import com.offcanon.infrastructure.memory.InMemorySnapshotRepository;
import com.offcanon.infrastructure.process.LocalCommandExecutor;
import com.offcanon.infrastructure.process.ProcessRunner;
import com.offcanon.infrastructure.promotion.LocalPromotionAdapter;
import com.offcanon.infrastructure.verification.TrustedVerificationAdapter;
import com.offcanon.infrastructure.workspace.LocalWorkspaceAdapter;
import com.offcanon.port.PromotionPort;
import com.offcanon.port.PromotionLockPort;
import com.offcanon.project.domain.Project;
import com.offcanon.verification.domain.VerificationResult;
import com.offcanon.workspace.domain.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromotionSnapshotBindingTest {
    @TempDir
    Path temp;

    @Test
    void promotesTheSealedResultInsteadOfLaterWorkspaceChanges() throws Exception {
        Fixture fixture = fixture(List.of("java -version"));
        Experiment experiment = fixture.verifiedExperiment();
        assertEquals(normalized(fixture.canonical.resolve("service.txt")), normalized(fixture.snapshotsPath(experiment.baseSnapshotId()).resolve("service.txt")));
        Files.writeString(experiment.workspacePath().resolve("service.txt"), "tampered after verification\n");

        PromotionApplicationService.PromotionOutcome outcome = fixture.promotions.promote(experiment.id());

        assertTrue(outcome.promoted(), outcome.toString());
        assertEquals("experiment result\n", normalized(fixture.canonical.resolve("service.txt")));
        assertFalse(normalized(fixture.canonical.resolve("service.txt")).contains("tampered"));
    }

    @Test
    void promotionPreparingEventReportsTheDurablePromotingState() throws Exception {
        Fixture fixture = fixture(List.of("java -version"));
        Experiment experiment = fixture.verifiedExperiment();

        PromotionApplicationService.PromotionOutcome outcome = fixture.promotions.promote(experiment.id());

        assertTrue(outcome.promoted(), outcome.toString());
        var event = fixture.events.after(experiment.id(), 0).stream()
                .filter(item -> item.type().equals("PROMOTION_PREPARING"))
                .findFirst()
                .orElseThrow();
        assertEquals("PROMOTING", event.payload().get("status"));
    }

    @Test
    void failedCandidateVerificationLeavesCanonicalUntouched() throws Exception {
        Fixture fixture = fixture(List.of("java -offcanon-invalid-option"));
        Experiment experiment = fixture.verifiedExperiment();

        PromotionApplicationService.PromotionOutcome outcome = fixture.promotions.promote(experiment.id());

        assertFalse(outcome.promoted());
        assertEquals("PROMOTION_VERIFICATION_FAILED", outcome.status());
        assertEquals("base\n", normalized(fixture.canonical.resolve("service.txt")));
        assertTrue(fixture.evidence.findByExperimentId(experiment.id()).stream()
                .anyMatch(item -> item.kind().equals("PROMOTION_VERIFICATION")
                        && item.snapshotId().equals(experiment.resultSnapshotId())));
    }

    @Test
    void adapterThatReportsNoApplyIsClassifiedFromCanonicalBase() throws Exception {
        Fixture fixture = fixture(List.of("java -version"), noApplyAdapter((project, base, candidate) ->
                new PromotionPort.PromotionResult(false, List.of())));
        Experiment experiment = fixture.verifiedExperiment();

        PromotionApplicationService.PromotionOutcome outcome = fixture.promotions.promote(experiment.id());

        assertFalse(outcome.promoted());
        assertEquals("PROMOTION_ABORTED", outcome.status());
        assertEquals(ExperimentStatus.VERIFIED, fixture.experiments.findById(experiment.id()).orElseThrow().status());
        assertEquals("base\n", normalized(fixture.canonical.resolve("service.txt")));
    }

    @Test
    void canonicalFingerprintWinsWhenAdapterReportsNoApplyAfterWritingCandidate() throws Exception {
        Fixture fixture = fixture(List.of("java -version"), noApplyAdapter((project, base, candidate) -> {
            try {
                Files.copy(candidate.resolve("service.txt"), project.canonicalPath().resolve("service.txt"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
            return new PromotionPort.PromotionResult(false, List.of());
        }));
        Experiment experiment = fixture.verifiedExperiment();

        PromotionApplicationService.PromotionOutcome outcome = fixture.promotions.promote(experiment.id());

        assertTrue(outcome.promoted(), outcome.toString());
        assertEquals("experiment result\n", normalized(fixture.canonical.resolve("service.txt")));
        assertEquals(ExperimentStatus.PROMOTED, fixture.experiments.findById(experiment.id()).orElseThrow().status());
    }

    @Test
    void ambiguousCanonicalAfterNoApplyRequiresRecovery() throws Exception {
        Fixture fixture = fixture(List.of("java -version"), noApplyAdapter((project, base, candidate) -> {
            try {
                Files.writeString(project.canonicalPath().resolve("service.txt"), "external\n");
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
            return new PromotionPort.PromotionResult(false, List.of());
        }));
        Experiment experiment = fixture.verifiedExperiment();

        PromotionApplicationService.PromotionOutcome outcome = fixture.promotions.promote(experiment.id());

        assertFalse(outcome.promoted());
        assertEquals("RECOVERY_REQUIRED", outcome.status());
        assertEquals(ExperimentStatus.RECOVERY_REQUIRED, fixture.experiments.findById(experiment.id()).orElseThrow().status());
        assertEquals("external\n", normalized(fixture.canonical.resolve("service.txt")));
    }

    @Test
    void parallelExperimentsStayIsolatedAndTheUnpromotedWorldBecomesStale() throws Exception {
        Fixture fixture = fixture(List.of("java -version"));
        Experiment first = fixture.verifiedExperiment("first result\n");
        Experiment second = fixture.verifiedExperiment("second result\n");

        assertEquals("base\n", normalized(fixture.canonical.resolve("service.txt")));
        assertEquals("first result\n", normalized(first.workspacePath().resolve("service.txt")));
        assertEquals("second result\n", normalized(second.workspacePath().resolve("service.txt")));

        PromotionApplicationService.PromotionOutcome firstOutcome = fixture.promotions.promote(first.id());
        PromotionApplicationService.PromotionOutcome secondOutcome = fixture.promotions.promote(second.id());

        assertTrue(firstOutcome.promoted(), firstOutcome.toString());
        assertFalse(secondOutcome.promoted());
        assertEquals("STALE", secondOutcome.status());
        assertEquals(ExperimentStatus.STALE, fixture.experiments.findById(second.id()).orElseThrow().status());
        assertEquals("first result\n", normalized(fixture.canonical.resolve("service.txt")));
        assertEquals("second result\n", normalized(second.workspacePath().resolve("service.txt")));
    }

    @Test
    void simultaneousPromotionRequestsEnterTheFinalCriticalSectionOnlyOnceAtATime() throws Exception {
        BarrierPromotionLock lock = new BarrierPromotionLock();
        Fixture fixture = fixture(List.of("java -version"), new LocalPromotionAdapter(lock), lock);
        Experiment experiment = fixture.verifiedExperiment();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> fixture.promotions.promote(experiment.id()));
            var second = executor.submit(() -> fixture.promotions.promote(experiment.id()));
            // Promotion preparation re-fingerprints isolated Git trees and
            // reruns trusted verification; allow the Windows subprocess path
            // enough time without weakening the final-section assertion.
            List<PromotionApplicationService.PromotionOutcome> outcomes = List.of(
                    first.get(120, TimeUnit.SECONDS), second.get(120, TimeUnit.SECONDS));

            assertEquals(1, outcomes.stream().filter(PromotionApplicationService.PromotionOutcome::promoted).count());
            assertEquals(1, lock.maxActive.get());
            assertEquals("experiment result\n", normalized(fixture.canonical.resolve("service.txt")));
            assertEquals(ExperimentStatus.PROMOTED, fixture.experiments.findById(experiment.id()).orElseThrow().status());
        }
    }

    @Test
    void candidateMutationAfterLockRecheckIsRejectedBeforeCanonicalWrite() throws Exception {
        InMemoryPromotionLock lock = new InMemoryPromotionLock();
        Fixture fixture = fixture(List.of("java -version"), new MutatingCandidateAdapter(lock), lock);
        Experiment experiment = fixture.verifiedExperiment();

        PromotionApplicationService.PromotionOutcome outcome = fixture.promotions.promote(experiment.id());

        assertFalse(outcome.promoted());
        assertEquals("PROMOTION_ABORTED", outcome.status());
        assertEquals("base\n", normalized(fixture.canonical.resolve("service.txt")));
        assertEquals(ExperimentStatus.VERIFIED, fixture.experiments.findById(experiment.id()).orElseThrow().status());
    }

    private Fixture fixture(List<String> verificationCommands) throws Exception {
        InMemoryPromotionLock lock = new InMemoryPromotionLock();
        return fixture(verificationCommands, new LocalPromotionAdapter(lock), lock);
    }

    private Fixture fixture(List<String> verificationCommands, PromotionPort promotionPort) throws Exception {
        return fixture(verificationCommands, promotionPort, new InMemoryPromotionLock());
    }

    private Fixture fixture(List<String> verificationCommands,
                            PromotionPort promotionPort,
                            PromotionLockPort promotionLock) throws Exception {
        Path canonical = temp.resolve("canonical-" + UUID.randomUUID());
        Files.createDirectories(canonical);
        run(canonical, "git", "init", "-q");
        run(canonical, "git", "config", "user.email", "offcanon-test@example.invalid");
        run(canonical, "git", "config", "user.name", "Offcanon Test");
        Files.writeString(canonical.resolve("service.txt"), "base\n");
        run(canonical, "git", "add", "service.txt");
        run(canonical, "git", "commit", "-qm", "initial");

        Path data = temp.resolve("data-" + UUID.randomUUID());
        ProcessRunner runner = new ProcessRunner();
        GitSnapshotAdapter snapshots = new GitSnapshotAdapter(runner, data.toString());
        LocalWorkspaceAdapter workspaces = new LocalWorkspaceAdapter(data.toString());
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        InMemorySnapshotRepository snapshotRepository = new InMemorySnapshotRepository();
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        InMemoryEvidenceRepository evidence = new InMemoryEvidenceRepository();
        InMemoryEventSink events = new InMemoryEventSink();
        TrustedVerificationAdapter verification = new TrustedVerificationAdapter(
                new LocalCommandExecutor(runner), evidence, snapshots, snapshotRepository, 10);
        Project project = projects.save(Project.create(java.util.UUID.randomUUID(), "demo", canonical, verificationCommands, Instant.now()));
        PromotionApplicationService promotions = new PromotionApplicationService(experiments, projects,
                snapshotRepository, snapshots, workspaces, promotionPort,
                promotionLock, events, verification, new InMemoryPromotionJournal());
        return new Fixture(canonical, project, snapshots, workspaces, snapshotRepository, experiments,
                evidence, events, promotions);
    }

    private String normalized(Path path) throws Exception {
        return Files.readString(path).replace("\r\n", "\n");
    }

    private void run(Path cwd, String... command) {
        ProcessRunner.ProcessResult result = new ProcessRunner().run(List.of(command), cwd, java.util.Map.of(), java.time.Duration.ofSeconds(20));
        if (result.exitCode() != 0) throw new AssertionError(result.stderr());
    }

    private record Fixture(Path canonical,
                           Project project,
                           GitSnapshotAdapter snapshots,
                           LocalWorkspaceAdapter workspaces,
                            InMemorySnapshotRepository snapshotRepository,
                            InMemoryExperimentRepository experiments,
                            InMemoryEvidenceRepository evidence,
                            InMemoryEventSink events,
                            PromotionApplicationService promotions) {
        Path snapshotsPath(UUID snapshotId) {
            return snapshotRepository.findById(snapshotId).orElseThrow().materializedPath();
        }
        Experiment verifiedExperiment() throws Exception {
            return verifiedExperiment("experiment result\n");
        }

        Experiment verifiedExperiment(String resultContent) throws Exception {
            Snapshot base = snapshots.capture(project);
            snapshotRepository.save(base);
            Experiment experiment = Experiment.create(project.id(), UUID.randomUUID(), "change service", Instant.now());
            experiments.save(experiment);
            experiment.beginSnapshot();
            experiments.save(experiment);
            experiment.attachBase(base.id(), workspaces.materialize(base, experiment.id()));
            experiments.save(experiment);
            experiment.start();
            experiments.save(experiment);
            Files.writeString(experiment.workspacePath().resolve("service.txt"), resultContent);
            experiment.markAgentCompleted("done");
            experiments.save(experiment);
            Snapshot result = snapshots.captureWorkspace(project, experiment.workspacePath(), base.fingerprint());
            snapshotRepository.save(result);
            experiment.sealResult(result.id());
            experiments.save(experiment);
            experiment.beginVerification();
            experiments.save(experiment);
            experiment.markVerified(VerificationResult.passed(List.of()));
            experiments.save(experiment);
            return experiment;
        }
    }

    private static final class BarrierPromotionLock implements PromotionLockPort {
        private final CyclicBarrier ready = new CyclicBarrier(2);
        private final InMemoryPromotionLock delegate = new InMemoryPromotionLock();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();

        @Override
        public <T> T withProjectLock(UUID projectId, java.util.function.Supplier<T> action) {
            try {
                ready.await(10, TimeUnit.SECONDS);
            } catch (Exception error) {
                throw new AssertionError("Promotion requests did not reach the barrier", error);
            }
            return delegate.withProjectLock(projectId, () -> {
                int current = active.incrementAndGet();
                maxActive.accumulateAndGet(current, Math::max);
                try {
                    return action.get();
                } finally {
                    active.decrementAndGet();
                }
            });
        }

        @Override
        public void assertHeld(UUID projectId) {
            delegate.assertHeld(projectId);
        }
    }

    private static final class MutatingCandidateAdapter implements PromotionPort {
        private final LocalPromotionAdapter delegate;

        private MutatingCandidateAdapter(PromotionLockPort promotionLock) {
            this.delegate = new LocalPromotionAdapter(promotionLock);
        }

        @Override
        public PromotionPlan plan(Project project, Snapshot base, Experiment experiment, Path candidate) {
            return delegate.plan(project, base, experiment, candidate);
        }

        @Override
        public PromotionResult apply(Project project,
                                     Snapshot base,
                                     Experiment experiment,
                                     Path candidate,
                                     PromotionPlan expectedPlan) {
            try {
                Files.writeString(candidate.resolve("service.txt"), "unverified mutation\n");
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
            return delegate.apply(project, base, experiment, candidate, expectedPlan);
        }
    }

    private PromotionPort noApplyAdapter(NoApplyBehavior behavior) {
        LocalPromotionAdapter planner = new LocalPromotionAdapter(new InMemoryPromotionLock());
        return new PromotionPort() {
            @Override
            public PromotionPlan plan(Project project, Snapshot base, Experiment experiment, Path candidate) {
                return planner.plan(project, base, experiment, candidate);
            }

            @Override
            public PromotionResult apply(Project project,
                                         Snapshot base,
                                         Experiment experiment,
                                         Path candidate,
                                         PromotionPlan expectedPlan) {
                return behavior.apply(project, base, candidate);
            }
        };
    }

    @FunctionalInterface
    private interface NoApplyBehavior {
        PromotionPort.PromotionResult apply(Project project, Snapshot base, Path candidate);
    }
}
