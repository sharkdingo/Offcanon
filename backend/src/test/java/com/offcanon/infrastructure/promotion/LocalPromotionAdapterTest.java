package com.offcanon.infrastructure.promotion;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.infrastructure.memory.InMemoryPromotionLock;
import com.offcanon.port.PromotionLockPort;
import com.offcanon.port.PromotionPort;
import com.offcanon.project.domain.Project;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.verification.domain.VerificationResult;
import com.offcanon.workspace.domain.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPromotionAdapterTest {
    @TempDir
    Path temp;

    @Test
    void appliesOnlyTheExperimentDelta() throws Exception {
        Path canonical = temp.resolve("canonical");
        Path basePath = temp.resolve("base");
        Path candidate = temp.resolve("candidate");
        Files.createDirectories(canonical);
        Files.createDirectories(basePath);
        Files.createDirectories(candidate);
        Files.writeString(canonical.resolve("a.txt"), "old\n");
        Files.writeString(canonical.resolve("remove.txt"), "remove\n");
        Files.writeString(basePath.resolve("a.txt"), "old\n");
        Files.writeString(basePath.resolve("remove.txt"), "remove\n");
        Files.writeString(candidate.resolve("a.txt"), "new\n");
        Files.writeString(candidate.resolve("add.txt"), "added\n");

        Project project = Project.create(java.util.UUID.randomUUID(), "demo", canonical, List.of(), Instant.now());
        Experiment experiment = Experiment.create(project.id(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), candidate);
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), project.id(), "base", basePath, Instant.now(), List.of("a.txt", "remove.txt"), List.of());

        AdapterFixture fixture = adapter();
        var plan = fixture.adapter().plan(project, snapshot, experiment, candidate);
        var result = apply(fixture, project, snapshot, experiment, candidate, plan);

        assertTrue(result.applied());
        assertEquals(List.of("a.txt", "add.txt", "remove.txt"), plan.touchedFiles().stream().sorted().toList());
        assertEquals(plan.touchedFiles().stream().sorted().toList(), plan.preimageHashes().keySet().stream().sorted().toList());
        assertEquals("ABSENT", plan.preimageHashes().get("add.txt"));
        assertEquals(List.of("a.txt", "add.txt", "remove.txt"), result.changedFiles().stream().sorted().toList());
        assertEquals("new\n", Files.readString(canonical.resolve("a.txt")));
        assertEquals("added\n", Files.readString(canonical.resolve("add.txt")));
        assertTrue(Files.notExists(canonical.resolve("remove.txt")));
    }

    @Test
    void refusesAChangedCanonicalPreimage() throws Exception {
        Path canonical = temp.resolve("canonical");
        Path basePath = temp.resolve("base");
        Path candidate = temp.resolve("candidate");
        Files.createDirectories(canonical);
        Files.createDirectories(basePath);
        Files.createDirectories(candidate);
        Files.writeString(canonical.resolve("a.txt"), "human change\n");
        Files.writeString(basePath.resolve("a.txt"), "old\n");
        Files.writeString(candidate.resolve("a.txt"), "new\n");

        Project project = Project.create(java.util.UUID.randomUUID(), "demo", canonical, List.of(), Instant.now());
        Experiment experiment = Experiment.create(project.id(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), candidate);
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), project.id(), "base", basePath, Instant.now(), List.of("a.txt"), List.of());

        DomainException error = assertThrows(DomainException.class,
                () -> apply(adapter(), project, snapshot, experiment, candidate));

        assertEquals("STALE_DURING_PROMOTION", error.code());
        assertEquals("human change\n", Files.readString(canonical.resolve("a.txt")));
    }

    @Test
    void refusesSensitivePromotionPaths() throws Exception {
        Path canonical = temp.resolve("canonical-sensitive");
        Path basePath = temp.resolve("base-sensitive");
        Path candidate = temp.resolve("candidate-sensitive");
        Files.createDirectories(canonical);
        Files.createDirectories(basePath);
        Files.createDirectories(candidate);
        Files.writeString(candidate.resolve(".env"), "SECRET=do-not-promote\n");

        Project project = Project.create(java.util.UUID.randomUUID(), "demo", canonical, List.of(), Instant.now());
        Experiment experiment = Experiment.create(project.id(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), candidate);
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), project.id(), "base", basePath, Instant.now(), List.of(), List.of());

        DomainException error = assertThrows(DomainException.class,
                () -> apply(adapter(), project, snapshot, experiment, candidate));

        assertEquals("PROMOTION_PROTECTED_PATH", error.code());
        assertTrue(Files.notExists(canonical.resolve(".env")));
    }

    @Test
    void promotesExplicitEnvironmentTemplates() throws Exception {
        Path canonical = temp.resolve("canonical-template");
        Path basePath = temp.resolve("base-template");
        Path candidate = temp.resolve("candidate-template");
        Files.createDirectories(canonical);
        Files.createDirectories(basePath);
        Files.createDirectories(candidate);
        Files.writeString(candidate.resolve(".env.example"), "TOKEN=replace-me\n");

        Project project = Project.create(java.util.UUID.randomUUID(), "demo", canonical, List.of(), Instant.now());
        Experiment experiment = Experiment.create(project.id(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), candidate);
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), project.id(), "base", basePath,
                Instant.now(), List.of(), List.of());

        var result = apply(adapter(), project, snapshot, experiment, candidate);

        assertTrue(result.applied());
        assertEquals("TOKEN=replace-me\n", Files.readString(canonical.resolve(".env.example")));
    }

    @Test
    void promotesModeOnlyChanges() throws Exception {
        Path canonical = temp.resolve("canonical-mode");
        Path basePath = temp.resolve("base-mode");
        Path candidate = temp.resolve("candidate-mode");
        Files.createDirectories(canonical);
        Files.createDirectories(basePath);
        Files.createDirectories(candidate);
        Path canonicalScript = canonical.resolve("run.sh");
        Path baseScript = basePath.resolve("run.sh");
        Path candidateScript = candidate.resolve("run.sh");
        Files.writeString(canonicalScript, "echo run\n");
        Files.writeString(baseScript, "echo run\n");
        Files.writeString(candidateScript, "echo run\n");
        try {
            Files.setPosixFilePermissions(candidateScript, java.util.Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException error) {
            Assumptions.assumeTrue(false, "POSIX permissions are unavailable on this workstation");
        }

        Project project = Project.create(UUID.randomUUID(), "demo", canonical, List.of(), Instant.now());
        Experiment experiment = Experiment.create(project.id(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), candidate);
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), project.id(), "base", basePath, Instant.now(),
                List.of("run.sh"), List.of());

        var fixture = adapter();
        var plan = fixture.adapter().plan(project, snapshot, experiment, candidate);
        assertTrue(plan.touchedFiles().contains("run.sh"));
        apply(fixture, project, snapshot, experiment, candidate, plan);

        assertTrue(Files.isExecutable(canonicalScript));
    }

    @Test
    void promotesExecutableContentChanges() throws Exception {
        Path canonical = temp.resolve("canonical-executable-content");
        Path basePath = temp.resolve("base-executable-content");
        Path candidate = temp.resolve("candidate-executable-content");
        Files.createDirectories(canonical);
        Files.createDirectories(basePath);
        Files.createDirectories(candidate);
        Path canonicalScript = canonical.resolve("run.sh");
        Path baseScript = basePath.resolve("run.sh");
        Path candidateScript = candidate.resolve("run.sh");
        Files.writeString(canonicalScript, "echo old\n");
        Files.writeString(baseScript, "echo old\n");
        Files.writeString(candidateScript, "echo new\n");
        try {
            Files.setPosixFilePermissions(candidateScript, java.util.Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException error) {
            Assumptions.assumeTrue(false, "POSIX permissions are unavailable on this workstation");
        }

        Project project = Project.create(UUID.randomUUID(), "demo", canonical, List.of(), Instant.now());
        Experiment experiment = Experiment.create(project.id(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), candidate);
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), project.id(), "base", basePath, Instant.now(),
                List.of("run.sh"), List.of());

        var fixture = adapter();
        var plan = fixture.adapter().plan(project, snapshot, experiment, candidate);
        apply(fixture, project, snapshot, experiment, candidate, plan);

        assertEquals("echo new\n", Files.readString(canonicalScript));
        assertTrue(Files.isExecutable(canonicalScript));
    }

    @Test
    void refusesProtectedPromotionPathsRegardlessOfCase() throws Exception {
        Path canonical = temp.resolve("canonical-case");
        Path basePath = temp.resolve("base-case");
        Path candidate = temp.resolve("candidate-case");
        Files.createDirectories(canonical);
        Files.createDirectories(basePath);
        Files.createDirectories(candidate.resolve(".GIT"));
        Files.writeString(candidate.resolve(".GIT/config"), "unsafe\n");

        Project project = Project.create(java.util.UUID.randomUUID(), "demo", canonical, List.of(), Instant.now());
        Experiment experiment = Experiment.create(project.id(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), candidate);
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), project.id(), "base", basePath, Instant.now(), List.of(), List.of());

        DomainException error = assertThrows(DomainException.class,
                () -> apply(adapter(), project, snapshot, experiment, candidate));

        assertEquals("PROMOTION_PROTECTED_PATH", error.code());
        assertTrue(Files.notExists(canonical.resolve(".git/config")));
    }

    @Test
    void refusesCanonicalSymlinkParent() throws Exception {
        Path canonical = temp.resolve("canonical-link");
        Path outside = temp.resolve("outside-link");
        Path basePath = temp.resolve("base-link");
        Path candidate = temp.resolve("candidate-link");
        Files.createDirectories(canonical);
        Files.createDirectories(outside);
        Files.createDirectories(basePath);
        Files.createDirectories(candidate.resolve("linked"));
        try {
            Files.createSymbolicLink(canonical.resolve("linked"), outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException error) {
            Assumptions.assumeTrue(false, "Symlinks are unavailable on this workstation");
        }
        Files.writeString(candidate.resolve("linked/file.txt"), "new\n");

        Project project = Project.create(java.util.UUID.randomUUID(), "demo", canonical, List.of(), Instant.now());
        Experiment experiment = Experiment.create(project.id(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), candidate);
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), project.id(), "base", basePath, Instant.now(), List.of(), List.of());

        DomainException error = assertThrows(DomainException.class,
                () -> apply(adapter(), project, snapshot, experiment, candidate));

        assertEquals("PROMOTION_SYMLINK_BLOCKED", error.code());
        assertTrue(Files.notExists(outside.resolve("file.txt")));
    }

    @Test
    void refusesToRollbackAfterPromotionLockIsLost() throws Exception {
        Path canonical = temp.resolve("canonical-lock-loss");
        Path basePath = temp.resolve("base-lock-loss");
        Path candidate = temp.resolve("candidate-lock-loss");
        Files.createDirectories(canonical);
        Files.createDirectories(basePath);
        Files.createDirectories(candidate);
        Files.writeString(canonical.resolve("a.txt"), "old-a\n");
        Files.writeString(canonical.resolve("b.txt"), "old-b\n");
        Files.writeString(basePath.resolve("a.txt"), "old-a\n");
        Files.writeString(basePath.resolve("b.txt"), "old-b\n");
        Files.writeString(candidate.resolve("a.txt"), "new-a\n");
        Files.writeString(candidate.resolve("b.txt"), "new-b\n");

        Project project = Project.create(UUID.randomUUID(), "demo", canonical, List.of(), Instant.now());
        Experiment experiment = Experiment.create(project.id(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), candidate);
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), project.id(), "base", basePath, Instant.now(),
                List.of("a.txt", "b.txt"), List.of());
        AtomicInteger checks = new AtomicInteger();
        com.offcanon.port.PromotionLockPort lock = new com.offcanon.port.PromotionLockPort() {
            @Override
            public <T> T withProjectLock(UUID ignored, Supplier<T> action) {
                return action.get();
            }

            @Override
            public void assertHeld(UUID ignored) {
                int count = checks.incrementAndGet();
                if (count == 3) {
                    try {
                        Files.writeString(canonical.resolve("b.txt"), "changed-by-human\n");
                    } catch (java.io.IOException failure) {
                        throw new AssertionError(failure);
                    }
                }
                if (count >= 5) {
                    throw new DomainException("PROMOTION_LOCK_LOST", "simulated lock loss during rollback");
                }
            }
        };

        DomainException error = assertThrows(DomainException.class,
                () -> apply(new AdapterFixture(new LocalPromotionAdapter(lock), lock), project, snapshot, experiment, candidate));

        assertEquals("PROMOTION_LOCK_LOST", error.code());
        // The first operation remains applied because ownership was lost before
        // rollback could be proven safe. Recovery must inspect this state rather
        // than letting a stale worker write the canonical tree.
        assertEquals("new-a\n", Files.readString(canonical.resolve("a.txt")));
        assertEquals("changed-by-human\n", Files.readString(canonical.resolve("b.txt")));
    }

    private AdapterFixture adapter() {
        InMemoryPromotionLock lock = new InMemoryPromotionLock();
        return new AdapterFixture(new LocalPromotionAdapter(lock), lock);
    }

    private PromotionPort.PromotionResult apply(AdapterFixture fixture,
                                                  Project project,
                                                  Snapshot base,
                                                  Experiment experiment,
                                                  Path candidate) {
        return apply(fixture, project, base, experiment, candidate,
                fixture.adapter().plan(project, base, experiment, candidate));
    }

    private PromotionPort.PromotionResult apply(AdapterFixture fixture,
                                                  Project project,
                                                  Snapshot base,
                                                  Experiment experiment,
                                                  Path candidate,
                                                  PromotionPort.PromotionPlan plan) {
        return fixture.lock().withProjectLock(project.id(),
                () -> fixture.adapter().apply(project, base, experiment, candidate, plan));
    }

    private record AdapterFixture(LocalPromotionAdapter adapter, PromotionLockPort lock) {
    }
}
