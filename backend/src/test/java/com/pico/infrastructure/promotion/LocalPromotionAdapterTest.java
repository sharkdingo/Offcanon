package com.pico.infrastructure.promotion;

import com.pico.experiment.domain.Experiment;
import com.pico.project.domain.Project;
import com.pico.shared.domain.DomainException;
import com.pico.verification.domain.VerificationResult;
import com.pico.workspace.domain.Snapshot;
import org.junit.jupiter.api.Test;
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

        var result = new LocalPromotionAdapter().apply(project, snapshot, experiment, candidate);

        assertTrue(result.applied());
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
}
