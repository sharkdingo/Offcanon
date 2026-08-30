package com.offcanon.infrastructure.workspace;

import com.offcanon.infrastructure.process.ProcessRunner;
import com.offcanon.experiment.domain.Experiment;
import com.offcanon.workspace.domain.Snapshot;
import com.offcanon.project.domain.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalWorkspaceAdapterTest {
    @TempDir
    Path temp;

    @Test
    void materializesAnIndependentCleanGitBaselineForTheExperiment() throws Exception {
        Path source = Files.createDirectories(temp.resolve("snapshot"));
        Files.createDirectories(source.resolve("src"));
        Files.writeString(source.resolve("src/app.js"), "export const value = 1\n");
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), UUID.randomUUID(), "base", source,
                Instant.now(), List.of("src/app.js"), List.of());
        ProcessRunner runner = new ProcessRunner();
        Path workspace = new LocalWorkspaceAdapter(temp.resolve("data").toString(), runner)
                .materialize(snapshot, UUID.randomUUID());

        assertTrue(Files.isDirectory(workspace.resolve(".git")));
        assertEquals(workspace.toRealPath(), Path.of(
                git(runner, workspace, "rev-parse", "--show-toplevel").stdout().trim()).toRealPath());
        assertTrue(git(runner, workspace, "status", "--short").stdout().isBlank());

        Files.writeString(workspace.resolve("src/app.js"), "export const value = 2\n");
        assertTrue(git(runner, workspace, "status", "--short").stdout().contains("src/app.js"));
        assertTrue(git(runner, workspace, "diff", "--", "src/app.js").stdout().contains("value = 2"));
    }

    @Test
    void tracksFilesThatWereIgnoredInTheCanonicalSnapshot() throws Exception {
        Path source = Files.createDirectories(temp.resolve("ignored-snapshot"));
        Files.writeString(source.resolve(".gitignore"), "generated.txt\n");
        Files.writeString(source.resolve("generated.txt"), "kept in the snapshot\n");
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), UUID.randomUUID(), "base", source,
                Instant.now(), List.of(".gitignore", "generated.txt"), List.of());
        ProcessRunner runner = new ProcessRunner();
        Path workspace = new LocalWorkspaceAdapter(temp.resolve("ignored-data").toString(), runner)
                .materialize(snapshot, UUID.randomUUID());

        ProcessRunner.ProcessResult tracked = git(runner, workspace, "ls-files", "--error-unmatch", "generated.txt");
        assertEquals("generated.txt", tracked.stdout().trim());
        assertTrue(git(runner, workspace, "status", "--short").stdout().isBlank());
    }

    @Test
    void continuationKeepsCanonicalGitBaselineWhileCarryingDraftChanges() throws Exception {
        Path baseSource = Files.createDirectories(temp.resolve("continuation-base/src"));
        Files.writeString(baseSource.resolve("app.js"), "export const value = 1\n");
        Files.writeString(baseSource.getParent().resolve("removed.txt"), "old\n");
        Path draftSource = Files.createDirectories(temp.resolve("continuation-draft/src"));
        Files.writeString(draftSource.resolve("app.js"), "export const value = 2\n");
        Files.writeString(draftSource.getParent().resolve("added.txt"), "new\n");
        Files.createDirectories(draftSource.getParent().resolve(".git"));
        Files.writeString(draftSource.getParent().resolve(".git/leak"), "must not be copied");
        UUID projectId = UUID.randomUUID();
        Snapshot base = new Snapshot(UUID.randomUUID(), projectId, "base", baseSource.getParent(),
                Instant.now(), List.of("src/app.js", "removed.txt"), List.of());
        Snapshot draft = new Snapshot(UUID.randomUUID(), projectId, "draft", draftSource.getParent(),
                Instant.now(), List.of("src/app.js", "added.txt"), List.of());
        ProcessRunner runner = new ProcessRunner();

        Path workspace = new LocalWorkspaceAdapter(temp.resolve("continuation-data").toString(), runner)
                .materializeContinuation(base, draft, UUID.randomUUID());

        String status = git(runner, workspace, "status", "--short").stdout();
        assertTrue(status.contains("src/app.js"));
        assertTrue(status.contains("removed.txt"));
        assertTrue(status.contains("added.txt"));
        assertEquals("export const value = 1", git(runner, workspace, "show", "HEAD:src/app.js").stdout().trim());
        assertEquals("export const value = 2\n", Files.readString(workspace.resolve("src/app.js")));
        assertFalse(Files.exists(workspace.resolve(".git/leak")));
    }

    @Test
    void givesVerificationWorkspaceItsOwnGitObservationBaseline() throws Exception {
        Path source = Files.createDirectories(temp.resolve("verification-snapshot"));
        Files.writeString(source.resolve("check.txt"), "verified\n");
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), UUID.randomUUID(), "result", source,
                Instant.now(), List.of("check.txt"), List.of());
        Project project = Project.create(java.util.UUID.randomUUID(), "demo", temp.resolve("canonical"), List.of(), Instant.now());
        Experiment experiment = Experiment.create(project.id(), UUID.randomUUID(), "check", Instant.now());
        ProcessRunner runner = new ProcessRunner();

        Path workspace = new LocalWorkspaceAdapter(temp.resolve("verification-data").toString(), runner)
                .createVerificationWorkspace(snapshot, experiment);

        assertTrue(Files.isDirectory(workspace.resolve(".git")));
        assertTrue(git(runner, workspace, "status", "--short").stdout().isBlank());
        Files.writeString(workspace.resolve("check.txt"), "changed\n");
        assertTrue(git(runner, workspace, "diff", "--", "check.txt").stdout().contains("changed"));
    }

    @Test
    void givesPromotionCandidateItsOwnGitObservationBaseline() throws Exception {
        Path source = Files.createDirectories(temp.resolve("promotion-snapshot"));
        Files.writeString(source.resolve("check.txt"), "candidate\n");
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), UUID.randomUUID(), "result", source,
                Instant.now(), List.of("check.txt"), List.of());
        Project project = Project.create(java.util.UUID.randomUUID(), "demo", temp.resolve("canonical"), List.of(), Instant.now());
        Experiment experiment = Experiment.create(project.id(), UUID.randomUUID(), "promote", Instant.now());
        ProcessRunner runner = new ProcessRunner();

        Path candidate = new LocalWorkspaceAdapter(temp.resolve("promotion-data").toString(), runner)
                .createPromotionCandidate(snapshot, experiment);

        assertTrue(Files.isDirectory(candidate.resolve(".git")));
        assertTrue(git(runner, candidate, "status", "--short").stdout().isBlank());
        Files.writeString(candidate.resolve("check.txt"), "changed\n");
        assertTrue(git(runner, candidate, "diff", "--", "check.txt").stdout().contains("changed"));
    }

    @Test
    void removesMaterializedWorkspaceWhenGitInitializationFails() throws Exception {
        Path source = Files.createDirectories(temp.resolve("failed-snapshot"));
        Files.writeString(source.resolve("check.txt"), "content\n");
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), UUID.randomUUID(), "base", source,
                Instant.now(), List.of("check.txt"), List.of());
        UUID experimentId = UUID.randomUUID();
        Path expected = temp.resolve("failed-data").resolve("experiments").resolve(experimentId.toString());
        LocalWorkspaceAdapter adapter = new LocalWorkspaceAdapter(temp.resolve("failed-data").toString(),
                new GitInitFailureRunner());

        assertThrows(RuntimeException.class, () -> adapter.materialize(snapshot, experimentId));
        assertFalse(Files.exists(expected), "a failed materialization must not leave a partial workspace");
    }

    @Test
    void removesVerificationAndPromotionDirectoriesWhenInitializationFails() throws Exception {
        Path source = Files.createDirectories(temp.resolve("failed-result"));
        Files.writeString(source.resolve("check.txt"), "content\n");
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), UUID.randomUUID(), "result", source,
                Instant.now(), List.of("check.txt"), List.of());
        Project project = Project.create(java.util.UUID.randomUUID(), "demo", temp.resolve("canonical-failed"), List.of(), Instant.now());
        Experiment experiment = Experiment.create(project.id(), UUID.randomUUID(), "check", Instant.now());
        LocalWorkspaceAdapter adapter = new LocalWorkspaceAdapter(temp.resolve("failed-derived-data").toString(),
                new GitInitFailureRunner());

        assertThrows(RuntimeException.class, () -> adapter.createVerificationWorkspace(snapshot, experiment));
        assertThrows(RuntimeException.class, () -> adapter.createPromotionCandidate(snapshot, experiment));
        assertTrue(children(temp.resolve("failed-derived-data").resolve("verification-workspaces")
                .resolve(experiment.id().toString())).isEmpty());
        assertTrue(children(temp.resolve("failed-derived-data").resolve("promotion-candidates")
                .resolve(experiment.id().toString())).isEmpty());
    }

    private List<Path> children(Path root) throws Exception {
        if (!Files.isDirectory(root)) return List.of();
        try (var stream = Files.list(root)) {
            return stream.toList();
        }
    }

    private static final class GitInitFailureRunner extends ProcessRunner {
        @Override
        public ProcessResult run(List<String> command, Path cwd, Map<String, String> environment, Duration timeout) {
            return new ProcessResult(1, "", "simulated git failure", Duration.ZERO, false);
        }
    }

    private ProcessRunner.ProcessResult git(ProcessRunner runner, Path cwd, String... arguments) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(List.of("git", "-C", cwd.toString()));
        command.addAll(List.of(arguments));
        ProcessRunner.ProcessResult result = runner.run(command, cwd, Map.of(), Duration.ofSeconds(20));
        assertEquals(0, result.exitCode(), result.stderr());
        return result;
    }
}
