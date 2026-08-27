package com.pico.infrastructure.promotion;

import com.pico.experiment.domain.Experiment;
import com.pico.project.domain.Project;
import com.pico.shared.domain.DomainException;
import com.pico.verification.domain.VerificationResult;
import com.pico.workspace.domain.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

        Project project = Project.create("demo", canonical, List.of(), Instant.now());
        Experiment experiment = Experiment.create(project.id(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), candidate);
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), project.id(), "base", basePath, Instant.now(), List.of("a.txt", "remove.txt"), List.of());

        LocalPromotionAdapter adapter = new LocalPromotionAdapter();
        var plan = adapter.plan(project, snapshot, experiment, candidate);
        var result = adapter.apply(project, snapshot, experiment, candidate);

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

        Project project = Project.create("demo", canonical, List.of(), Instant.now());
        Experiment experiment = Experiment.create(project.id(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), candidate);
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), project.id(), "base", basePath, Instant.now(), List.of("a.txt"), List.of());

        DomainException error = assertThrows(DomainException.class,
                () -> new LocalPromotionAdapter().apply(project, snapshot, experiment, candidate));

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

        Project project = Project.create("demo", canonical, List.of(), Instant.now());
        Experiment experiment = Experiment.create(project.id(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), candidate);
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), project.id(), "base", basePath, Instant.now(), List.of(), List.of());

        DomainException error = assertThrows(DomainException.class,
                () -> new LocalPromotionAdapter().apply(project, snapshot, experiment, candidate));

        assertEquals("PROMOTION_PROTECTED_PATH", error.code());
        assertTrue(Files.notExists(canonical.resolve(".env")));
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

        Project project = Project.create("demo", canonical, List.of(), Instant.now());
        Experiment experiment = Experiment.create(project.id(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), candidate);
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), project.id(), "base", basePath, Instant.now(), List.of(), List.of());

        DomainException error = assertThrows(DomainException.class,
                () -> new LocalPromotionAdapter().apply(project, snapshot, experiment, candidate));

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

        Project project = Project.create("demo", canonical, List.of(), Instant.now());
        Experiment experiment = Experiment.create(project.id(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), candidate);
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), project.id(), "base", basePath, Instant.now(), List.of(), List.of());

        DomainException error = assertThrows(DomainException.class,
                () -> new LocalPromotionAdapter().apply(project, snapshot, experiment, candidate));

        assertEquals("PROMOTION_SYMLINK_BLOCKED", error.code());
        assertTrue(Files.notExists(outside.resolve("file.txt")));
    }
}
