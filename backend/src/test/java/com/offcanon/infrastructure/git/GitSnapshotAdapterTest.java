package com.offcanon.infrastructure.git;

import com.offcanon.infrastructure.process.ProcessRunner;
import com.offcanon.project.domain.Project;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.workspace.domain.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitSnapshotAdapterTest {
    @TempDir
    Path temp;

    @Test
    void capturesDirtyTrackedAndUntrackedFilesWithoutChangingCanonicalIndex() throws Exception {
        Path repository = temp.resolve("repo");
        Files.createDirectories(repository);
        run(repository, "git", "init", "-q");
        run(repository, "git", "config", "user.email", "offcanon-test@example.invalid");
        run(repository, "git", "config", "user.name", "Offcanon Test");
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
        CanonicalGitState canonicalBefore = canonicalGitState(repository);
        Snapshot snapshot = adapter.capture(project);

        assertEquals(canonicalBefore, canonicalGitState(repository));
        assertTrue(hasStoredObjects(dataRoot.resolve("git-objects").resolve(project.id().toString())));
        assertTrue(snapshot.includedFiles().contains("tracked.txt"));
        assertTrue(snapshot.includedFiles().contains("new.txt"));
        assertTrue(snapshot.excludedFiles().stream().anyMatch(item -> item.path().equals(".env")));
        assertEquals("after\n", Files.readString(snapshot.materializedPath().resolve("tracked.txt")).replace("\r\n", "\n"));
        assertTrue(Files.exists(snapshot.materializedPath().resolve("new.txt")));
        assertFalse(Files.exists(snapshot.materializedPath().resolve(".env")));
        assertEquals(statusBefore, run(repository, "git", "status", "--porcelain=v1"));
    }

    @Test
    void exportIgnoreDoesNotRemoveAFileFromTheMaterializedTree() throws Exception {
        Path repository = initialise("export-ignore-repo");
        Files.writeString(repository.resolve(".gitattributes"), "kept.txt export-ignore\n");
        Files.writeString(repository.resolve("kept.txt"), "committed\n");
        run(repository, "git", "add", ".gitattributes", "kept.txt");
        run(repository, "git", "commit", "-qm", "export attributes");
        Files.writeString(repository.resolve("kept.txt"), "dirty working tree\n");

        Path dataRoot = temp.resolve("export-ignore-data");
        Project project = Project.create("demo", repository, List.of(), Instant.now());
        CanonicalGitState canonicalBefore = canonicalGitState(repository);
        Snapshot snapshot = new GitSnapshotAdapter(new ProcessRunner(), dataRoot.toString()).capture(project);

        assertEquals(canonicalBefore, canonicalGitState(repository));
        assertTrue(hasStoredObjects(dataRoot.resolve("git-objects").resolve(project.id().toString())));
        assertTrue(snapshot.includedFiles().contains("kept.txt"));
        assertEquals("dirty working tree\n",
                Files.readString(snapshot.materializedPath().resolve("kept.txt")).replace("\r\n", "\n"));
    }

    @Test
    void materializedSnapshotDoesNotShareFilesWithCanonical() throws Exception {
        Path repository = temp.resolve("repo");
        Files.createDirectories(repository);
        run(repository, "git", "init", "-q");
        run(repository, "git", "config", "user.email", "offcanon-test@example.invalid");
        run(repository, "git", "config", "user.name", "Offcanon Test");
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
        run(repository, "git", "config", "user.email", "offcanon-test@example.invalid");
        run(repository, "git", "config", "user.name", "Offcanon Test");
        Files.writeString(repository.resolve(".env"), "FAKE_SECRET=test-fixture-only\n");
        Files.writeString(repository.resolve("safe.txt"), "safe\n");
        run(repository, "git", "add", "-f", ".env", "safe.txt");
        run(repository, "git", "commit", "-qm", "initial");

        GitSnapshotAdapter adapter = new GitSnapshotAdapter(new ProcessRunner(), temp.resolve("tracked-sensitive-data").toString());
        Snapshot snapshot = adapter.capture(Project.create("demo", repository, List.of(), Instant.now()));

        assertFalse(Files.exists(snapshot.materializedPath().resolve(".env")));
        assertTrue(Files.exists(snapshot.materializedPath().resolve("safe.txt")));
    }

    @Test
    void sealsAnExperimentWorkspaceIntoAnIndependentResultSnapshot() throws Exception {
        Path repository = temp.resolve("result-source");
        Files.createDirectories(repository);
        run(repository, "git", "init", "-q");
        run(repository, "git", "config", "user.email", "offcanon-test@example.invalid");
        run(repository, "git", "config", "user.name", "Offcanon Test");
        Files.writeString(repository.resolve("service.txt"), "base\n");
        run(repository, "git", "add", "service.txt");
        run(repository, "git", "commit", "-qm", "initial");

        GitSnapshotAdapter adapter = new GitSnapshotAdapter(new ProcessRunner(), temp.resolve("result-data").toString());
        Project project = Project.create("demo", repository, List.of(), Instant.now());
        CanonicalGitState canonicalBeforeBase = canonicalGitState(repository);
        Snapshot base = adapter.capture(project);
        assertEquals(canonicalBeforeBase, canonicalGitState(repository));
        Path experiment = temp.resolve("result-workspace");
        copyTree(base.materializedPath(), experiment);
        Files.writeString(experiment.resolve("service.txt"), "experiment\n");
        Files.writeString(experiment.resolve("added.txt"), "new\n");

        CanonicalGitState canonicalBeforeResult = canonicalGitState(repository);
        Snapshot result = adapter.captureWorkspace(project, experiment, base.fingerprint());
        assertEquals(canonicalBeforeResult, canonicalGitState(repository));
        Files.writeString(experiment.resolve("service.txt"), "tampered later\n");

        assertEquals("experiment\n", Files.readString(result.materializedPath().resolve("service.txt")).replace("\r\n", "\n"));
        assertEquals("new\n", Files.readString(result.materializedPath().resolve("added.txt")).replace("\r\n", "\n"));
        assertEquals(result.fingerprint(), adapter.fingerprintWorkspace(project, result.materializedPath(), base.fingerprint()));
        assertEquals(canonicalBeforeResult, canonicalGitState(repository));
    }

    @Test
    void recordsGitIgnoredFilesAsExcluded() throws Exception {
        Path repository = initialise("ignored-repo");
        Files.writeString(repository.resolve(".gitignore"), "*.log\n");
        Files.writeString(repository.resolve("safe.txt"), "safe\n");
        Files.writeString(repository.resolve("debug.log"), "ignored\n");
        run(repository, "git", "add", ".gitignore", "safe.txt");
        run(repository, "git", "commit", "-qm", "initial");

        Snapshot snapshot = new GitSnapshotAdapter(new ProcessRunner(), temp.resolve("ignored-data").toString())
                .capture(Project.create("demo", repository, List.of(), Instant.now()));

        assertTrue(snapshot.excludedFiles().stream().anyMatch(item -> item.path().equals("debug.log")
                && item.reason().equals("git ignored")));
        assertFalse(Files.exists(snapshot.materializedPath().resolve("debug.log")));
    }

    @Test
    void rejectsNestedGitRepositoriesAsGitlinks() throws Exception {
        Path repository = initialise("nested-parent");
        Files.writeString(repository.resolve("root.txt"), "root\n");
        run(repository, "git", "add", "root.txt");
        run(repository, "git", "commit", "-qm", "initial");
        Path nested = repository.resolve("nested");
        Files.createDirectories(nested);
        run(nested, "git", "init", "-q");
        Files.writeString(nested.resolve("child.txt"), "child\n");
        run(nested, "git", "add", "child.txt");
        run(nested, "git", "-c", "user.email=offcanon-test@example.invalid", "-c", "user.name=Offcanon Test",
                "commit", "-qm", "nested");

        DomainException error = assertThrows(DomainException.class, () ->
                new GitSnapshotAdapter(new ProcessRunner(), temp.resolve("nested-data").toString())
                        .capture(Project.create("demo", repository, List.of(), Instant.now())));

        assertEquals("SNAPSHOT_GITLINK_UNSUPPORTED", error.code());
    }

    @Test
    void rejectsLfsPointersAndOversizedFiles() throws Exception {
        Path lfsRepository = initialise("lfs-repo");
        Files.writeString(lfsRepository.resolve("asset.bin"), "version https://git-lfs.github.com/spec/v1\n"
                + "oid sha256:" + "0".repeat(64) + "\nsize 123\n");
        run(lfsRepository, "git", "add", "asset.bin");
        run(lfsRepository, "git", "commit", "-qm", "lfs pointer");
        GitSnapshotAdapter lfsAdapter = new GitSnapshotAdapter(new ProcessRunner(), temp.resolve("lfs-data").toString());

        assertEquals("SNAPSHOT_LFS_UNSUPPORTED", assertThrows(DomainException.class,
                () -> lfsAdapter.capture(Project.create("lfs", lfsRepository, List.of(), Instant.now()))).code());
        assertTrue(directoryIsEmpty(temp.resolve("lfs-data").resolve("snapshots")));

        Path largeRepository = initialise("large-repo");
        try (var file = new java.io.RandomAccessFile(largeRepository.resolve("large.bin").toFile(), "rw")) {
            file.setLength(20L * 1024 * 1024 + 1);
        }
        GitSnapshotAdapter largeAdapter = new GitSnapshotAdapter(new ProcessRunner(), temp.resolve("large-data").toString());

        assertEquals("SNAPSHOT_FILE_TOO_LARGE", assertThrows(DomainException.class,
                () -> largeAdapter.capture(Project.create("large", largeRepository, List.of(), Instant.now()))).code());
        assertTrue(directoryIsEmpty(temp.resolve("large-data").resolve("snapshots")));
    }

    @Test
    void rejectsSubdirectoryScopeAndDataRootOverlap() throws Exception {
        Path repository = initialise("scope-repo");
        Files.createDirectories(repository.resolve("module"));

        GitSnapshotAdapter adapter = new GitSnapshotAdapter(new ProcessRunner(), temp.resolve("scope-data").toString());
        assertEquals("PROJECT_SCOPE_MISMATCH", assertThrows(DomainException.class,
                () -> adapter.validateProject(repository.resolve("module"))).code());

        GitSnapshotAdapter overlapping = new GitSnapshotAdapter(new ProcessRunner(), repository.resolve(".offcanon-data").toString());
        assertEquals("PROJECT_DATA_ROOT_OVERLAP", assertThrows(DomainException.class,
                () -> overlapping.validateProject(repository)).code());
    }

    @Test
    void detectsWorkspaceMutationDuringCapture() throws Exception {
        Path repository = initialise("racing-repo");
        Path file = repository.resolve("service.txt");
        Files.writeString(file, "before\n");
        run(repository, "git", "add", "service.txt");
        run(repository, "git", "commit", "-qm", "initial");
        ProcessRunner mutating = new ProcessRunner() {
            private boolean changed;

            @Override
            public ProcessResult run(List<String> command, Path cwd, java.util.Map<String, String> environment,
                                     java.time.Duration timeout) {
                ProcessResult result = super.run(command, cwd, environment, timeout);
                if (!changed && command.contains("ls-tree")) {
                    changed = true;
                    try {
                        Files.writeString(file, "during capture\n");
                    } catch (java.io.IOException error) {
                        throw new java.io.UncheckedIOException(error);
                    }
                }
                return result;
            }
        };

        DomainException error = assertThrows(DomainException.class, () ->
                new GitSnapshotAdapter(mutating, temp.resolve("race-data").toString())
                        .capture(Project.create("race", repository, List.of(), Instant.now())));

        assertEquals("SNAPSHOT_RACED", error.code());
    }

    private Path initialise(String name) throws Exception {
        Path repository = temp.resolve(name);
        Files.createDirectories(repository);
        run(repository, "git", "init", "-q");
        run(repository, "git", "config", "user.email", "offcanon-test@example.invalid");
        run(repository, "git", "config", "user.name", "Offcanon Test");
        return repository;
    }

    private void copyTree(Path source, Path destination) throws Exception {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
    }

    private String run(Path cwd, String... command) {
        ProcessRunner.ProcessResult result = new ProcessRunner().run(List.of(command), cwd, java.util.Map.of(), java.time.Duration.ofSeconds(20));
        if (result.exitCode() != 0) {
            throw new AssertionError(result.stderr());
        }
        return result.stdout().trim();
    }

    private CanonicalGitState canonicalGitState(Path repository) throws Exception {
        Path gitDirectory = Path.of(run(repository, "git", "rev-parse", "--absolute-git-dir"));
        return new CanonicalGitState(
                digestTree(gitDirectory.resolve("objects")),
                digestTree(gitDirectory.resolve("refs")),
                digestTree(gitDirectory.resolve("index")),
                digestTree(gitDirectory.resolve("packed-refs")),
                digestTree(gitDirectory.resolve("HEAD")));
    }

    private String digestTree(Path root) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            digest.update("missing".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest.digest());
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                String relative = root.equals(path) ? "." : root.relativize(path).toString().replace('\\', '/');
                digest.update(relative.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    digest.update((byte) 'D');
                } else if (Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    digest.update((byte) 'F');
                    try (var input = Files.newInputStream(path)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
                    }
                } else {
                    digest.update((byte) 'O');
                }
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private boolean hasStoredObjects(Path objectDirectory) throws Exception {
        try (var paths = Files.walk(objectDirectory)) {
            return paths.anyMatch(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        }
    }

    private boolean directoryIsEmpty(Path directory) throws Exception {
        if (!Files.exists(directory)) return true;
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    private record CanonicalGitState(String objects,
                                     String refs,
                                     String index,
                                     String packedRefs,
                                     String head) {
    }
}
