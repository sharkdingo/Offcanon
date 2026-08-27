package com.pico.infrastructure.git;

import com.pico.infrastructure.process.ProcessRunner;
import com.pico.project.domain.Project;
import com.pico.workspace.domain.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitSnapshotAdapterTest {
    @TempDir
    Path temp;

    @Test
    void capturesDirtyTrackedAndUntrackedFilesWithoutChangingCanonicalIndex() throws Exception {
        Path repository = temp.resolve("repo");
        Files.createDirectories(repository);
        run(repository, "git", "init", "-q");
        run(repository, "git", "config", "user.email", "pico-test@example.invalid");
        run(repository, "git", "config", "user.name", "PICO Test");
        Files.writeString(repository.resolve("tracked.txt"), "before\n");
        run(repository, "git", "add", "tracked.txt");
        run(repository, "git", "commit", "-qm", "initial");

        Files.writeString(repository.resolve("tracked.txt"), "after\n");
        Files.writeString(repository.resolve("new.txt"), "untracked\n");
        Files.writeString(repository.resolve(".env"), "SECRET=must-not-enter-snapshot\n");
        String statusBefore = run(repository, "git", "status", "--porcelain=v1");

        Path dataRoot = temp.resolve("data");
        GitSnapshotAdapter adapter = new GitSnapshotAdapter(new ProcessRunner(), dataRoot.toString());
        Project project = Project.create("demo", repository, List.of(), Instant.now());
        Snapshot snapshot = adapter.capture(project);

        assertTrue(snapshot.includedFiles().contains("tracked.txt"));
        assertTrue(snapshot.includedFiles().contains("new.txt"));
        assertTrue(snapshot.excludedFiles().stream().anyMatch(item -> item.path().equals(".env")));
        assertEquals("after\n", Files.readString(snapshot.materializedPath().resolve("tracked.txt")).replace("\r\n", "\n"));
        assertTrue(Files.exists(snapshot.materializedPath().resolve("new.txt")));
        assertFalse(Files.exists(snapshot.materializedPath().resolve(".env")));
        assertEquals(statusBefore, run(repository, "git", "status", "--porcelain=v1"));
    }

    @Test
    void materializedSnapshotDoesNotShareFilesWithCanonical() throws Exception {
        Path repository = temp.resolve("repo");
        Files.createDirectories(repository);
        run(repository, "git", "init", "-q");
        run(repository, "git", "config", "user.email", "pico-test@example.invalid");
        run(repository, "git", "config", "user.name", "PICO Test");
        Files.writeString(repository.resolve("file.txt"), "canonical\n");
        run(repository, "git", "add", "file.txt");
        run(repository, "git", "commit", "-qm", "initial");

        GitSnapshotAdapter adapter = new GitSnapshotAdapter(new ProcessRunner(), temp.resolve("data").toString());
        Snapshot snapshot = adapter.capture(Project.create("demo", repository, List.of(), Instant.now()));
        Files.writeString(snapshot.materializedPath().resolve("file.txt"), "experiment\n");

        assertEquals("canonical\n", Files.readString(repository.resolve("file.txt")));
    }

    @Test
    void excludesTrackedSensitiveFilesFromSnapshot() throws Exception {
        Path repository = temp.resolve("tracked-sensitive");
        Files.createDirectories(repository);
        run(repository, "git", "init", "-q");
        run(repository, "git", "config", "user.email", "pico-test@example.invalid");
        run(repository, "git", "config", "user.name", "PICO Test");
        Files.writeString(repository.resolve(".env"), "FAKE_SECRET=test-fixture-only\n");
        Files.writeString(repository.resolve("safe.txt"), "safe\n");
        run(repository, "git", "add", "-f", ".env", "safe.txt");
        run(repository, "git", "commit", "-qm", "initial");

        GitSnapshotAdapter adapter = new GitSnapshotAdapter(new ProcessRunner(), temp.resolve("tracked-sensitive-data").toString());
        Snapshot snapshot = adapter.capture(Project.create("demo", repository, List.of(), Instant.now()));

        assertFalse(Files.exists(snapshot.materializedPath().resolve(".env")));
        assertTrue(Files.exists(snapshot.materializedPath().resolve("safe.txt")));
    }

    private String run(Path cwd, String... command) {
        ProcessRunner.ProcessResult result = new ProcessRunner().run(List.of(command), cwd, java.util.Map.of(), java.time.Duration.ofSeconds(20));
        if (result.exitCode() != 0) {
            throw new AssertionError(result.stderr());
        }
        return result.stdout().trim();
    }
}
