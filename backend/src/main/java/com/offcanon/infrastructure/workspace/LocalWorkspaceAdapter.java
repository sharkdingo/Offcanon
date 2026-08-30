package com.offcanon.infrastructure.workspace;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.infrastructure.filesystem.GitFileMode;
import com.offcanon.infrastructure.process.ProcessRunner;
import com.offcanon.port.WorkspacePort;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.workspace.domain.Snapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class LocalWorkspaceAdapter implements WorkspacePort {
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(20);
    private final Path dataRoot;
    private final ProcessRunner processRunner;

    @org.springframework.beans.factory.annotation.Autowired
    public LocalWorkspaceAdapter(@Value("${offcanon.data-root}") String dataRoot,
                                 ProcessRunner processRunner) {
        this.dataRoot = Path.of(dataRoot).toAbsolutePath().normalize();
        this.processRunner = processRunner;
    }

    public LocalWorkspaceAdapter(String dataRoot) {
        this(dataRoot, new ProcessRunner());
    }

    @Override
    public void discard(Path workspace) {
        if (workspace == null) return;
        Path normalized = workspace.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized) || !isManagedRuntimePath(normalized)) {
            throw new DomainException("WORKSPACE_DISCARD_REJECTED",
                    "Workspace is outside the managed runtime directories");
        }
        try {
            deleteTree(normalized);
        } catch (IOException error) {
            throw new DomainException("WORKSPACE_DISCARD_FAILED",
                    error.getMessage() == null ? "Unable to discard workspace" : error.getMessage());
        }
    }

    @Override
    public Path materialize(Snapshot snapshot, UUID experimentId) {
        requireSnapshot(snapshot);
        requireExperimentId(experimentId);
        Path destination = dataRoot.resolve("experiments").resolve(experimentId.toString()).toAbsolutePath().normalize();
        copyTree(snapshot.materializedPath(), destination);
        try {
            initializeWorkspaceRepository(destination);
            return destination;
        } catch (RuntimeException error) {
            deleteQuietly(destination);
            throw error;
        }
    }

    @Override
    public Path materializeContinuation(Snapshot base, Snapshot carriedDraft, UUID experimentId) {
        requireSnapshot(base);
        requireSnapshot(carriedDraft);
        requireExperimentId(experimentId);
        if (!base.projectId().equals(carriedDraft.projectId())) {
            throw new DomainException("CONTINUATION_PROJECT_MISMATCH",
                    "Continuation draft belongs to a different project");
        }
        Path destination = dataRoot.resolve("experiments").resolve(experimentId.toString())
                .toAbsolutePath().normalize();
        copyTree(base.materializedPath(), destination);
        try {
            initializeWorkspaceRepository(destination);
            replaceWorkingTree(carriedDraft.materializedPath(), destination);
            return destination;
        } catch (RuntimeException error) {
            deleteQuietly(destination);
            throw error;
        }
    }

    @Override
    public Path createVerificationWorkspace(Snapshot result, Experiment experiment) {
        requireSnapshot(result);
        requireExperiment(experiment);
        Path destination = dataRoot.resolve("verification-workspaces").resolve(experiment.id().toString())
                .resolve("attempt-" + UUID.randomUUID())
                .toAbsolutePath().normalize();
        copyTree(result.materializedPath(), destination);
        try {
            initializeWorkspaceRepository(destination);
            return destination;
        } catch (RuntimeException error) {
            deleteQuietly(destination);
            throw error;
        }
    }

    @Override
    public Path createPromotionCandidate(Snapshot result, Experiment experiment) {
        requireSnapshot(result);
        requireExperiment(experiment);
        Path destination = dataRoot.resolve("promotion-candidates").resolve(experiment.id().toString())
                .resolve("attempt-" + UUID.randomUUID())
                .toAbsolutePath().normalize();
        copyTree(result.materializedPath(), destination);
        try {
            initializeWorkspaceRepository(destination);
            return destination;
        } catch (RuntimeException error) {
            deleteQuietly(destination);
            throw error;
        }
    }

    private void copyTree(Path source, Path destination) {
        boolean created = false;
        try {
            if (Files.isSymbolicLink(source) || !Files.isDirectory(source, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new DomainException("WORKSPACE_SOURCE_MISSING", "Workspace source does not exist: " + source);
            }
            if (destination.getParent() != null) {
                Files.createDirectories(destination.getParent());
            }
            try {
                if (Files.isSymbolicLink(destination)) {
                    throw new DomainException("WORKSPACE_DESTINATION_INVALID", "Workspace destination must not be a symbolic link: " + destination);
                }
                Files.createDirectory(destination);
                created = true;
            } catch (FileAlreadyExistsException error) {
                throw new DomainException("WORKSPACE_ALREADY_EXISTS", "Workspace already exists: " + destination);
            }
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(source) && Files.isSymbolicLink(dir)) {
                        throw new DomainException("WORKSPACE_SYMLINK_BLOCKED", "Symlink directory cannot be materialized: " + source.relativize(dir));
                    }
                    Path relative = source.relativize(dir);
                    Files.createDirectories(destination.resolve(relative));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(file)) {
                        throw new DomainException("WORKSPACE_SYMLINK_BLOCKED", "Symlink cannot be materialized: " + source.relativize(file));
                    }
                    Path relative = source.relativize(file);
                    Path target = destination.resolve(relative);
                    Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                    GitFileMode.apply(target, GitFileMode.read(file));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (RuntimeException error) {
            if (created) deleteQuietly(destination);
            throw error;
        } catch (IOException e) {
            if (created) deleteQuietly(destination);
            throw new DomainException("WORKSPACE_CREATE_FAILED", e.getMessage() == null ? "Unable to materialize workspace" : e.getMessage());
        }
    }

    private void requireSnapshot(Snapshot snapshot) {
        if (snapshot == null || snapshot.materializedPath() == null
                || Files.isSymbolicLink(snapshot.materializedPath())
                || !Files.isDirectory(snapshot.materializedPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new DomainException("WORKSPACE_SOURCE_MISSING", "Snapshot materialization is missing");
        }
    }

    private void requireExperimentId(UUID experimentId) {
        if (experimentId == null) throw new DomainException("WORKSPACE_EXPERIMENT_MISSING", "Experiment identity is missing");
    }

    private void requireExperiment(Experiment experiment) {
        if (experiment == null) throw new DomainException("WORKSPACE_EXPERIMENT_MISSING", "Experiment identity is missing");
        requireExperimentId(experiment.id());
    }

    private void replaceWorkingTree(Path source, Path destination) {
        try {
            if (!Files.isDirectory(source) || !Files.isDirectory(destination.resolve(".git"))) {
                throw new DomainException("WORKSPACE_SOURCE_MISSING",
                        "Continuation source or Git baseline does not exist");
            }
            try (var children = Files.list(destination)) {
                for (Path child : children.filter(path -> !path.getFileName().toString().equalsIgnoreCase(".git")).toList()) {
                    deleteTree(child);
                }
            }
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                    Path relative = source.relativize(directory);
                    if (!relative.toString().isEmpty() && isGitMetadata(relative)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!directory.equals(source) && Files.isSymbolicLink(directory)) {
                        throw new DomainException("WORKSPACE_SYMLINK_BLOCKED",
                                "Symlink directory cannot be carried into a continuation: " + relative);
                    }
                    Files.createDirectories(destination.resolve(relative));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path relative = source.relativize(file);
                    if (isGitMetadata(relative)) return FileVisitResult.CONTINUE;
                    if (Files.isSymbolicLink(file)) {
                        throw new DomainException("WORKSPACE_SYMLINK_BLOCKED",
                                "Symlink cannot be carried into a continuation: " + relative);
                    }
                    Path target = destination.resolve(relative);
                    Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                    GitFileMode.apply(target, GitFileMode.read(file));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException error) {
            throw new DomainException("WORKSPACE_CREATE_FAILED",
                    error.getMessage() == null ? "Unable to carry continuation draft" : error.getMessage());
        }
    }

    private boolean isGitMetadata(Path relative) {
        return relative.getNameCount() > 0 && relative.getName(0).toString().equalsIgnoreCase(".git");
    }

    private boolean isManagedRuntimePath(Path path) {
        for (String rootName : List.of("experiments", "verification-workspaces", "promotion-candidates")) {
            Path root = dataRoot.resolve(rootName).toAbsolutePath().normalize();
            if (!path.equals(root) && path.startsWith(root)) return true;
        }
        return false;
    }

    private void deleteQuietly(Path root) {
        try {
            deleteTree(root);
        } catch (IOException ignored) {
            // A later runtime cleanup pass can remove an incomplete workspace.
        }
    }

    private void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void initializeWorkspaceRepository(Path workspace) {
        runGit(workspace, List.of("-c", "init.defaultBranch=offcanon", "init", "-q"),
                "Unable to initialize Experiment Git metadata");
        // The snapshot has already applied Offcanon exclusions. Force-add the remaining tree so a
        // tracked file that happens to match .gitignore is still visible to git status/diff.
        runGit(workspace, List.of("-c", "core.autocrlf=false", "add", "-f", "-A", "--", "."),
                "Unable to stage the Experiment baseline");
        runGit(workspace, List.of(
                        "-c", "user.name=Offcanon",
                        "-c", "user.email=offcanon@localhost",
                        "-c", "core.autocrlf=false",
                        "commit", "-q", "--allow-empty", "-m", "Offcanon experiment baseline"),
                "Unable to commit the Experiment baseline");
    }

    private void runGit(Path workspace, List<String> arguments, String message) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("core.longpaths=true");
        command.add("-C");
        command.add(workspace.toString());
        command.addAll(arguments);
        ProcessRunner.ProcessResult result = processRunner.run(command, workspace, Map.of(), GIT_TIMEOUT);
        if (result.exitCode() != 0 || result.timedOut()) {
            String detail = result.stderr().isBlank() ? result.stdout() : result.stderr();
            throw new DomainException("WORKSPACE_GIT_INIT_FAILED",
                    message + (detail.isBlank() ? "" : ": " + detail.trim()));
        }
    }
}
