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
import java.nio.file.LinkOption;
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
        if (Files.isSymbolicLink(normalized) || !isManagedRuntimePath(normalized)
                || !hasNoSymbolicComponents(normalized)) {
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
        Path destination = managedDestination("experiments", experimentId.toString());
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
        Path destination = managedDestination("experiments", experimentId.toString());
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
        Path destination = managedDestination("verification-workspaces", experiment.id().toString(),
                "attempt-" + UUID.randomUUID());
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
        Path destination = managedDestination("promotion-candidates", experiment.id().toString(),
                "attempt-" + UUID.randomUUID());
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
                ensureSafeDirectory(destination.getParent(), "WORKSPACE_DESTINATION_INVALID",
                        "Workspace destination parent must be a real directory");
            }
            try {
                if (Files.isSymbolicLink(destination)
                        || !hasNoSymbolicComponents(destination.getParent())) {
                    throw new DomainException("WORKSPACE_DESTINATION_INVALID", "Workspace destination must not be a symbolic link: " + destination);
                }
                Files.createDirectory(destination);
                created = true;
                if (!hasNoSymbolicComponents(destination)
                        || !Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                    throw new DomainException("WORKSPACE_DESTINATION_INVALID",
                            "Workspace destination must be a real directory");
                }
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
                    createSafeDirectories(destination.resolve(relative), destination);
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
                || !Files.isDirectory(snapshot.materializedPath(), LinkOption.NOFOLLOW_LINKS)
                || !hasNoSymbolicComponents(snapshot.materializedPath())) {
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
            if (!hasNoSymbolicComponents(source)
                    || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                    || !hasNoSymbolicComponents(destination)
                    || !Files.isDirectory(destination.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
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
                    createSafeDirectories(destination.resolve(relative), destination);
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

    /**
     * Creates a runtime destination only below real, application-owned
     * directories.  A lexical startsWith check alone is insufficient when a
     * managed directory has been replaced by a symlink.
     */
    private Path managedDestination(String rootName, String... children) {
        Path root = dataRoot.resolve(rootName).toAbsolutePath().normalize();
        ensureSafeDirectory(root, "WORKSPACE_DESTINATION_INVALID",
                "Workspace destination root must be a real directory");
        Path destination = root;
        for (String child : children) {
            destination = destination.resolve(child).toAbsolutePath().normalize();
            if (!destination.startsWith(root)) {
                throw new DomainException("WORKSPACE_DESTINATION_INVALID",
                        "Workspace destination escapes the managed runtime directory");
            }
        }
        if (destination.getParent() != null) {
            ensureSafeDirectory(destination.getParent(), "WORKSPACE_DESTINATION_INVALID",
                    "Workspace destination parent must be a real directory");
        }
        if (Files.isSymbolicLink(destination)) {
            throw new DomainException("WORKSPACE_DESTINATION_INVALID",
                    "Workspace destination must not be a symbolic link");
        }
        return destination;
    }

    private void ensureSafeDirectory(Path directory, String code, String message) {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!hasNoSymbolicComponents(normalized)) {
            throw new DomainException(code, message);
        }
        try {
            Files.createDirectories(normalized);
        } catch (IOException error) {
            throw new DomainException(code, message);
        }
        if (Files.isSymbolicLink(normalized)
                || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                || !hasNoSymbolicComponents(normalized)) {
            throw new DomainException(code, message);
        }
    }

    private void createSafeDirectories(Path directory, Path root) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = directory.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot)
                || !hasNoSymbolicComponents(normalizedRoot)) {
            throw new DomainException("WORKSPACE_DESTINATION_INVALID",
                    "Workspace destination escapes the managed runtime directory");
        }
        Path current = normalizedRoot;
        Path relative = normalizedRoot.relativize(normalized);
        for (Path part : relative) {
            current = current.resolve(part.toString());
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)
                        || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new DomainException("WORKSPACE_DESTINATION_INVALID",
                            "Workspace destination contains an invalid directory component");
                }
            } else {
                Files.createDirectory(current);
            }
        }
    }

    private boolean hasNoSymbolicComponents(Path path) {
        Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                return false;
            }
            current = current.getParent();
        }
        return true;
    }

    private void deleteQuietly(Path root) {
        try {
            deleteTree(root);
        } catch (IOException ignored) {
            // A later runtime cleanup pass can remove an incomplete workspace.
        }
    }

    private void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(root) || !hasNoSymbolicComponents(root)) {
            throw new IOException("Refusing to delete a symbolic-link runtime path");
        }
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
