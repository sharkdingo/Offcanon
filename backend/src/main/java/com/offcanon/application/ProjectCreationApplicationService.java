package com.offcanon.application;

import com.offcanon.infrastructure.process.ProcessRunner;
import com.offcanon.port.SnapshotPort;
import com.offcanon.shared.domain.DomainException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/** Creates a new local project without weakening the existing Git boundary. */
@Service
public final class ProjectCreationApplicationService {
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(20);
    private static final Set<String> WINDOWS_RESERVED_DEVICE_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
    private final ProcessRunner processRunner;
    private final SnapshotPort snapshots;
    private final ProjectApplicationService projects;
    private final Path dataRoot;
    private final ConcurrentMap<Path, Object> creationLocks = new ConcurrentHashMap<>();

    @Autowired
    public ProjectCreationApplicationService(ProcessRunner processRunner,
                                             SnapshotPort snapshots,
                                             ProjectApplicationService projects,
                                             @Value("${offcanon.data-root}") String dataRoot) {
        this(processRunner, snapshots, projects, Path.of(dataRoot));
    }

    ProjectCreationApplicationService(ProcessRunner processRunner,
                                      SnapshotPort snapshots,
                                      ProjectApplicationService projects,
                                      Path dataRoot) {
        this.processRunner = processRunner;
        this.snapshots = snapshots;
        this.projects = projects;
        this.dataRoot = dataRoot.toAbsolutePath().normalize();
    }

    public ProjectApplicationService.RegistrationResult create(UUID ownerId,
                                                                String name,
                                                                String canonicalPath,
                                                                List<String> verificationCommands) {
        validateMetadata(name, canonicalPath, verificationCommands);
        Path target = parseTarget(canonicalPath);
        ensureOutsideDataRoot(target);

        Object lock = creationLocks.computeIfAbsent(target, ignored -> new Object());
        try {
            synchronized (lock) {
                return createUnderLock(ownerId, name, target, verificationCommands);
            }
        } finally {
            creationLocks.remove(target, lock);
        }
    }

    private ProjectApplicationService.RegistrationResult createUnderLock(UUID ownerId,
                                                                          String name,
                                                                          Path target,
                                                                          List<String> verificationCommands) {

        Path parent = target.getParent();
        if (parent == null) {
            throw new DomainException("PROJECT_PARENT_REQUIRED",
                    "A new project must be created inside an existing directory");
        }
        Path realParent = requireParent(parent);
        boolean createdDirectory = false;
        boolean gitTouched = false;
        try {
            if (Files.isSymbolicLink(target)) {
                throw new DomainException("PROJECT_TARGET_INVALID",
                        "The new project directory must not be a symbolic link");
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                        || !hasNoSymbolicComponents(target)) {
                    throw new DomainException("PROJECT_TARGET_INVALID",
                            "The new project path is not a usable directory");
                }
                if (!isEmptyDirectory(target)) {
                    throw new DomainException("PROJECT_TARGET_NOT_EMPTY",
                            "The new project directory must be empty");
                }
            } else {
                try {
                    Files.createDirectory(target);
                    createdDirectory = true;
                } catch (IOException | SecurityException error) {
                    throw new DomainException("PROJECT_DIRECTORY_CREATE_FAILED",
                            "Unable to create the new project directory");
                }
            }

            // Re-check after the atomic create/empty-directory check. A
            // concurrent replacement must never make git init operate through
            // a symlink or in a different parent.
            if (Files.isSymbolicLink(target) || !hasNoSymbolicComponents(target)) {
                throw new DomainException("PROJECT_TARGET_INVALID",
                        "The new project directory must not contain symbolic links");
            }
            Path realTarget;
            try {
                realTarget = target.toRealPath();
            } catch (IOException | SecurityException error) {
                throw new DomainException("PROJECT_TARGET_INVALID",
                        "Unable to resolve the new project directory");
            }
            if (!realParent.equals(realTarget.getParent())) {
                throw new DomainException("PROJECT_TARGET_INVALID",
                        "The new project directory moved while it was being created");
            }
            ensureOutsideDataRoot(realTarget);
            if (!isEmptyDirectory(realTarget)) {
                throw new DomainException("PROJECT_TARGET_NOT_EMPTY",
                        "The new project directory must be empty");
            }

            // Remember that git init may leave a partial .git directory even
            // when the process reports an error, so failed registrations can
            // clean up only artifacts created by this request.
            gitTouched = true;
            initializeGit(realTarget);
            // Keep the existing adapter as the source of truth for the exact
            // repository root and all canonical-path safety checks.
            Path canonicalRoot = snapshots.resolveProjectRoot(realTarget);
            return projects.registerWithOutcome(ownerId, name,
                    canonicalRoot.toString(), verificationCommands);
        } catch (RuntimeException error) {
            cleanupCreatedDirectory(target, createdDirectory, gitTouched);
            throw error;
        }
    }

    private void validateMetadata(String name, String canonicalPath, List<String> verificationCommands) {
        if (name == null || name.isBlank()) {
            throw new DomainException("PROJECT_NAME_MISSING", "Project name is required");
        }
        if (name.trim().length() > 200) {
            throw new DomainException("PROJECT_NAME_TOO_LARGE", "Project name cannot exceed 200 characters");
        }
        if (canonicalPath == null || canonicalPath.isBlank()) {
            throw new DomainException("PROJECT_PATH_MISSING", "Project path is required");
        }
        if (canonicalPath.trim().length() > 4_096) {
            throw new DomainException("PROJECT_PATH_TOO_LARGE", "Project path cannot exceed 4096 characters");
        }
        List<String> commands = verificationCommands == null ? List.of() : verificationCommands;
        if (commands.size() > 20) {
            throw new DomainException("VERIFICATION_POLICY_TOO_LARGE",
                    "Configure no more than 20 verification commands");
        }
        for (String command : commands) {
            // Blank lines are ignored by the shared project policy
            // normalizer; an all-empty input is a valid deferred policy.
            if (command == null || command.isBlank()) continue;
            if (command.trim().length() > 1_000) {
                throw new DomainException("VERIFICATION_COMMAND_TOO_LARGE",
                        "Each verification command cannot exceed 1000 characters");
            }
        }
    }

    private Path parseTarget(String rawPath) {
        final Path path;
        try {
            path = Path.of(rawPath.trim());
        } catch (RuntimeException error) {
            throw new DomainException("PROJECT_PATH_INVALID", "Unable to parse the project path");
        }
        if (!path.isAbsolute()) {
            throw new DomainException("PROJECT_PATH_ABSOLUTE_REQUIRED",
                    "Project path must be an absolute path on this machine");
        }
        for (Path segment : path) {
            String value = segment.toString();
            if (".".equals(value) || "..".equals(value)) {
                throw new DomainException("PROJECT_PATH_INVALID",
                        "The new project path cannot contain . or .. segments");
            }
        }
        if (path.getFileName() == null) {
            throw new DomainException("PROJECT_PARENT_REQUIRED",
                    "A new project must be created inside an existing directory");
        }
        if (!isDirectoryNameSafe(path.getFileName().toString())) {
            throw new DomainException("PROJECT_PATH_INVALID",
                    "The final project path component is not a valid directory name");
        }
        return path.toAbsolutePath().normalize();
    }

    private boolean isDirectoryNameSafe(String name) {
        if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.indexOf(':') >= 0 || name.matches(".*[\\\"<>|?*].*")
                || name.endsWith(".") || name.endsWith(" ")) {
            return false;
        }
        String device = name.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (WINDOWS_RESERVED_DEVICE_NAMES.contains(device)) return false;
        for (int index = 0; index < name.length(); index++) {
            if (Character.isISOControl(name.charAt(index))) return false;
        }
        return true;
    }

    private Path requireParent(Path parent) {
        try {
            if (Files.isSymbolicLink(parent) || !hasNoSymbolicComponents(parent)) {
                throw new DomainException("PROJECT_PARENT_INVALID",
                        "The parent directory must not contain symbolic links");
            }
            if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw new DomainException("PROJECT_PARENT_NOT_FOUND",
                        "The parent directory must already exist");
            }
        } catch (DomainException error) {
            throw error;
        } catch (SecurityException error) {
            throw new DomainException("PROJECT_PARENT_INVALID",
                    "Unable to inspect the parent directory");
        }
        try {
            Path real = parent.toRealPath();
            if (!Files.isReadable(real) || !Files.isWritable(real)) {
                throw new DomainException("PROJECT_PARENT_NOT_WRITABLE",
                        "The parent directory must be readable and writable");
            }
            return real;
        } catch (IOException | SecurityException error) {
            throw new DomainException("PROJECT_PARENT_INVALID",
                    "Unable to resolve the parent directory");
        }
    }

    private void ensureOutsideDataRoot(Path candidate) {
        Path effectiveDataRoot = dataRoot;
        try {
            if (Files.exists(dataRoot, LinkOption.NOFOLLOW_LINKS)) {
                effectiveDataRoot = dataRoot.toRealPath();
            }
        } catch (IOException | SecurityException error) {
            throw new DomainException("PROJECT_DATA_ROOT_INVALID",
                    "Unable to resolve the Offcanon data directory");
        }
        Path normalized = candidate.toAbsolutePath().normalize();
        if (normalized.startsWith(effectiveDataRoot) || effectiveDataRoot.startsWith(normalized)) {
            throw new DomainException("PROJECT_DATA_ROOT_OVERLAP",
                    "Offcanon data root and canonical repository must not contain one another");
        }
    }

    private void initializeGit(Path target) {
        try {
            ProcessRunner.ProcessResult result = processRunner.run(
                    List.of("git", "init", "-q"),
                    target, Map.of(), GIT_TIMEOUT);
            if (result.exitCode() != 0 || result.timedOut() || result.cancelled()) {
                String detail = result.stderr().isBlank() ? result.stdout() : result.stderr();
                throw new DomainException("PROJECT_GIT_INIT_FAILED",
                        "Unable to initialize Git" + (detail.isBlank() ? "" : ": " + detail.trim()));
            }
        } catch (DomainException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new DomainException("PROJECT_GIT_INIT_FAILED", "Unable to initialize Git");
        }
    }

    private boolean isEmptyDirectory(Path directory) {
        try (Stream<Path> children = Files.list(directory)) {
            return children.findAny().isEmpty();
        } catch (IOException | SecurityException error) {
            throw new DomainException("PROJECT_TARGET_INVALID",
                    "Unable to inspect the new project directory");
        }
    }

    private boolean hasNoSymbolicComponents(Path path) {
        Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            try {
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) return false;
            } catch (SecurityException error) {
                return false;
            }
            current = current.getParent();
        }
        return true;
    }

    private void cleanupCreatedDirectory(Path target, boolean createdDirectory, boolean gitTouched) {
        try {
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(target) || !hasNoSymbolicComponents(target)) return;
        } catch (SecurityException ignored) {
            return;
        }
        if (!createdDirectory && !gitTouched) return;
        final List<Path> children;
        try (Stream<Path> stream = Files.list(target)) {
            children = stream.toList();
        } catch (IOException | SecurityException ignored) {
            return;
        }
        try {
            if (createdDirectory && children.isEmpty()) {
                Files.deleteIfExists(target);
                return;
            }
            if (children.size() != 1
                    || !children.getFirst().getFileName().toString().equals(".git")) return;
            Path git = children.getFirst();
            if (Files.isSymbolicLink(git)) Files.deleteIfExists(git);
            else deleteTree(git);
            if (createdDirectory) Files.deleteIfExists(target);
        } catch (IOException ignored) {
            // Runtime cleanup can remove an orphaned initialization later.
        }
    }

    private void deleteTree(Path root) throws IOException {
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
}
