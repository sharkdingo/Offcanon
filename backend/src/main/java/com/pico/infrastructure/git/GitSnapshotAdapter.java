package com.pico.infrastructure.git;

import com.pico.infrastructure.process.ProcessRunner;
import com.pico.port.SnapshotPort;
import com.pico.project.domain.Project;
import com.pico.shared.domain.DomainException;
import com.pico.workspace.domain.Snapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class GitSnapshotAdapter implements SnapshotPort {
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(20);
    private final ProcessRunner processRunner;
    private final Path dataRoot;

    public GitSnapshotAdapter(ProcessRunner processRunner,
                              @Value("${pico.data-root}") String dataRoot) {
        this.processRunner = processRunner;
        this.dataRoot = Path.of(dataRoot).toAbsolutePath().normalize();
    }

    @Override
    public Snapshot capture(Project project) {
        Path root = requireGitRoot(project.canonicalPath());
        UUID snapshotId = UUID.randomUUID();
        Path snapshotPath = dataRoot.resolve("snapshots").resolve(snapshotId.toString());
        Path archive = null;
        try {
            Files.createDirectories(snapshotPath.getParent());
            archive = Files.createTempFile("pico-snapshot-", ".zip");
            String before = writeWorkingTree(root);
            ProcessRunner.ProcessResult archiveResult = runGit(root, List.of("archive", "--format=zip", "-o", archive.toString(), before));
            if (archiveResult.exitCode() != 0) {
                throw gitFailure("Unable to materialize snapshot archive", archiveResult);
            }

            List<String> included = new ArrayList<>();
            List<Snapshot.ExcludedPath> excluded = new ArrayList<>();
            collectExcluded(root, excluded);
            extractArchive(archive, snapshotPath, included, excluded);

            String after = writeWorkingTree(root);
            if (!before.equals(after)) {
                deleteRecursively(snapshotPath);
                throw new DomainException("SNAPSHOT_RACED",
                        "The canonical workspace changed while the snapshot was captured");
            }
            return new Snapshot(snapshotId, project.id(), before, snapshotPath, Instant.now(), included, excluded);
        } catch (IOException e) {
            deleteQuietly(snapshotPath);
            throw new DomainException("SNAPSHOT_FAILED", e.getMessage() == null ? "Unable to capture snapshot" : e.getMessage());
        } finally {
            if (archive != null) {
                deleteQuietly(archive);
            }
        }
    }

    @Override
    public String currentFingerprint(Project project) {
        return writeWorkingTree(requireGitRoot(project.canonicalPath()));
    }

    private Path requireGitRoot(Path path) {
        if (!Files.isDirectory(path)) {
            throw new DomainException("PROJECT_PATH_NOT_FOUND", "Project path is not a directory: " + path);
        }
        ProcessRunner.ProcessResult result = runGit(path, List.of("rev-parse", "--show-toplevel"));
        if (result.exitCode() != 0) {
            throw new DomainException("PROJECT_NOT_GIT", "Project is not a Git repository: " + path);
        }
        try {
            return Path.of(result.stdout().trim()).toRealPath();
        } catch (IOException e) {
            throw new DomainException("PROJECT_PATH_INVALID", "Unable to resolve project path: " + path);
        }
    }

    private String writeWorkingTree(Path root) {
        Path index;
        try {
            index = Files.createTempFile("pico-index-", ".tmp");
            Files.deleteIfExists(index);
        } catch (IOException e) {
            throw new DomainException("SNAPSHOT_FAILED", "Unable to create temporary Git index");
        }
        try {
            Map<String, String> env = Map.of("GIT_INDEX_FILE", index.toString());
            ProcessRunner.ProcessResult readTree = runGit(root, List.of("read-tree", "HEAD"), env);
            if (readTree.exitCode() != 0) {
                readTree = runGit(root, List.of("read-tree", "--empty"), env);
                if (readTree.exitCode() != 0) {
                    throw gitFailure("Unable to initialise temporary index", readTree);
                }
            }
            List<String> addArgs = new ArrayList<>(List.of("add", "-A", "--", "."));
            addArgs.addAll(List.of(
                    ":(exclude,icase,glob)**/.env",
                    ":(exclude,icase,glob)**/.env.*",
                    ":(exclude,icase,glob)**/.git/**",
                    ":(exclude,icase,glob)**/.pico/**",
                    ":(exclude,glob)**/node_modules/**",
                    ":(exclude,glob)**/target/**",
                    ":(exclude,glob)**/build/**",
                    ":(exclude,glob)**/dist/**",
                    ":(exclude,glob)**/.idea/**",
                    ":(exclude,glob)**/.vscode/**"));
            ProcessRunner.ProcessResult add = runGit(root, addArgs, env);
            if (add.exitCode() != 0) {
                throw gitFailure("Unable to capture working tree", add);
            }
            removeExcludedIndexEntries(root, env);
            ProcessRunner.ProcessResult tree = runGit(root, List.of("write-tree"), env);
            if (tree.exitCode() != 0 || tree.stdout().isBlank()) {
                throw gitFailure("Unable to write snapshot tree", tree);
            }
            return tree.stdout().trim();
        } finally {
            deleteQuietly(index);
        }
    }

    private void removeExcludedIndexEntries(Path root, Map<String, String> env) {
        ProcessRunner.ProcessResult listed = runGit(root, List.of("ls-files"), env);
        if (listed.exitCode() != 0 || listed.stdout().isBlank()) return;
        for (String relative : listed.stdout().split("\\R")) {
            if (relative.isBlank() || exclusionReason(relative.replace('\\', '/')) == null) continue;
            ProcessRunner.ProcessResult removed = runGit(root, List.of("update-index", "--force-remove", "--", relative), env);
            if (removed.exitCode() != 0) {
                throw gitFailure("Unable to exclude protected snapshot path", removed);
            }
        }
    }

    private void extractArchive(Path archive,
                                Path destination,
                                List<String> included,
                                List<Snapshot.ExcludedPath> excluded) throws IOException {
        Files.createDirectories(destination);
        Path realDestination = destination.toRealPath();
        try (InputStream input = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String relative = entry.getName().replace('\\', '/');
                Path target = destination.resolve(relative).normalize();
                if (!target.startsWith(destination)) {
                    throw new DomainException("SNAPSHOT_INVALID_ENTRY", "Archive entry escapes snapshot: " + relative);
                }
                String reason = exclusionReason(relative);
                if (reason != null) {
                    excluded.add(new Snapshot.ExcludedPath(relative, reason));
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    if (Files.exists(target) && !target.toRealPath().startsWith(realDestination)) {
                        throw new DomainException("SNAPSHOT_PATH_ESCAPE", "Snapshot target escapes destination: " + relative);
                    }
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                    included.add(relative);
                }
            }
        }
    }

    private void collectExcluded(Path root, List<Snapshot.ExcludedPath> excluded) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                if (directory.equals(root)) return FileVisitResult.CONTINUE;
                String relative = root.relativize(directory).toString().replace('\\', '/');
                String reason = exclusionReason(relative);
                if (reason != null) {
                    excluded.add(new Snapshot.ExcludedPath(relative, reason));
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                String reason = exclusionReason(relative);
                if (reason != null) excluded.add(new Snapshot.ExcludedPath(relative, reason));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private String exclusionReason(String relative) {
        String[] parts = relative.toLowerCase(Locale.ROOT).split("/");
        for (String part : parts) {
            if (part.equals(".git") || part.equals(".pico") || part.equals("node_modules")
                    || part.equals("target") || part.equals("build") || part.equals("dist")
                    || part.equals(".idea") || part.equals(".vscode")) {
                return "runtime or dependency directory";
            }
        }
        String fileName = parts[parts.length - 1];
        if (fileName.equals(".env") || fileName.startsWith(".env.")) {
            return "sensitive environment file";
        }
        return null;
    }

    private ProcessRunner.ProcessResult runGit(Path cwd, List<String> args) {
        return runGit(cwd, args, Map.of());
    }

    private ProcessRunner.ProcessResult runGit(Path cwd, List<String> args, Map<String, String> environment) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(cwd.toString());
        command.addAll(args);
        return processRunner.run(command, cwd, new HashMap<>(environment), GIT_TIMEOUT);
    }

    private DomainException gitFailure(String message, ProcessRunner.ProcessResult result) {
        String detail = result.stderr().isBlank() ? result.stdout() : result.stderr();
        return new DomainException("SNAPSHOT_FAILED", message + (detail.isBlank() ? "" : ": " + detail.trim()));
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteQuietly(Path path) {
        try {
            deleteRecursively(path);
        } catch (IOException ignored) {
            // Best-effort cleanup. The immutable snapshot remains outside canonical.
        }
    }
}
