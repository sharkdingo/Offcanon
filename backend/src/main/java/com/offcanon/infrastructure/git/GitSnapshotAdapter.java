package com.offcanon.infrastructure.git;

import com.offcanon.infrastructure.process.ProcessRunner;
import com.offcanon.infrastructure.filesystem.GitFileMode;
import com.offcanon.port.SnapshotPort;
import com.offcanon.project.domain.Project;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.shared.domain.SensitivePathPolicy;
import com.offcanon.workspace.domain.Snapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
public class GitSnapshotAdapter implements SnapshotPort {
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(20);
    private static final long MAX_SNAPSHOT_FILE_BYTES = 20L * 1024 * 1024;
    private static final String LFS_POINTER_HEADER = "version https://git-lfs.github.com/spec/v1";
    private final ProcessRunner processRunner;
    private final Path dataRoot;

    public GitSnapshotAdapter(ProcessRunner processRunner,
                              @Value("${offcanon.data-root}") String dataRoot) {
        this.processRunner = processRunner;
        this.dataRoot = Path.of(dataRoot).toAbsolutePath().normalize();
    }

    @Override
    public void validateProject(Path canonicalPath) {
        requireGitRoot(canonicalPath);
    }

    @Override
    public Path resolveProjectRoot(Path requestedPath) {
        return requireGitRoot(requestedPath);
    }

    @Override
    public Snapshot capture(Project project) {
        Path root = requireGitRoot(project.canonicalPath());
        GitObjectStore objectStore = objectStore(project, root);
        return captureTree(project, root, objectStore, () -> writeWorkingTree(root, objectStore));
    }

    @Override
    public Snapshot captureWorkspace(Project project, Path workspace, String parentFingerprint) {
        Path root = requireGitRoot(project.canonicalPath());
        Path source = requireWorkspace(workspace);
        GitObjectStore objectStore = objectStore(project, root);
        return captureTree(project, source, objectStore,
                () -> writeWorkspaceTree(root, source, parentFingerprint, objectStore));
    }

    @Override
    public void discard(Snapshot snapshot) {
        if (snapshot == null || snapshot.id() == null || snapshot.materializedPath() == null) {
            throw new DomainException("SNAPSHOT_DISCARD_REJECTED",
                    "Temporary snapshot identity is missing");
        }
        Path expected = dataRoot.resolve("snapshots").resolve(snapshot.id().toString())
                .toAbsolutePath().normalize();
        Path actual = snapshot.materializedPath().toAbsolutePath().normalize();
        if (!actual.equals(expected) || Files.isSymbolicLink(actual)
                || !hasNoSymbolicComponents(expected)
                || !hasNoSymbolicComponents(actual)) {
            throw new DomainException("SNAPSHOT_DISCARD_REJECTED",
                    "Temporary snapshot is outside the managed snapshot directory");
        }
        deleteQuietly(expected);
    }

    @Override
    public String fingerprintWorkspace(Project project, Path workspace, String parentFingerprint) {
        Path root = requireGitRoot(project.canonicalPath());
        return writeWorkspaceTree(root, requireWorkspace(workspace), parentFingerprint,
                objectStore(project, root));
    }

    private Snapshot captureTree(Project project,
                                 Path source,
                                 GitObjectStore objectStore,
                                 Supplier<String> fingerprint) {
        UUID snapshotId = UUID.randomUUID();
        Path snapshotPath = dataRoot.resolve("snapshots").resolve(snapshotId.toString());
        boolean completed = false;
        try {
            ensureManagedDirectory(snapshotPath.getParent());
            if (Files.exists(snapshotPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new DomainException("SNAPSHOT_DESTINATION_INVALID",
                        "Snapshot destination already exists");
            }
            // Reserve the leaf atomically before any content is copied.  This
            // prevents a pre-existing link or directory from being reused.
            Files.createDirectory(snapshotPath);
            Set<String> ignored = gitIgnored(project, source);
            String rawBefore = rawFingerprint(source, ignored);
            String before = fingerprint.get();
            Path gitRoot = requireGitRoot(project.canonicalPath());
            List<TreeEntry> treeFiles = listTreeFiles(gitRoot, before, objectStore.environment());

            List<String> included = new ArrayList<>();
            List<Snapshot.ExcludedPath> excluded = new ArrayList<>();
            collectExcluded(source, excluded, ignored);
            materializeTreeFiles(source, snapshotPath, treeFiles, included);

            String after = fingerprint.get();
            String rawAfter = rawFingerprint(source, ignored);
            if (!before.equals(after) || !rawBefore.equals(rawAfter)) {
                throw new DomainException("SNAPSHOT_RACED",
                        "The source workspace changed while the snapshot was captured");
            }
            Snapshot snapshot = new Snapshot(snapshotId, project.id(), before, snapshotPath, Instant.now(), included, excluded);
            completed = true;
            return snapshot;
        } catch (IOException e) {
            throw new DomainException("SNAPSHOT_FAILED", e.getMessage() == null ? "Unable to capture snapshot" : e.getMessage());
        } finally {
            if (!completed) deleteQuietly(snapshotPath);
        }
    }

    @Override
    public String currentFingerprint(Project project) {
        Path root = requireGitRoot(project.canonicalPath());
        return writeWorkingTree(root, objectStore(project, root));
    }

    private Path requireWorkspace(Path path) {
        if (path == null || Files.isSymbolicLink(path)
                || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || !hasNoSymbolicComponents(path)) {
            throw new DomainException("WORKSPACE_SOURCE_MISSING", "Workspace is not a directory: " + path);
        }
        try {
            Path real = path.toRealPath();
            if (Files.isSymbolicLink(real)) {
                throw new DomainException("WORKSPACE_PATH_INVALID", "Workspace root must not be a symbolic link: " + path);
            }
            return real;
        } catch (IOException error) {
            throw new DomainException("WORKSPACE_PATH_INVALID", "Unable to resolve workspace: " + path);
        }
    }

    private Path requireGitRoot(Path path) {
        if (!Files.isDirectory(path)) {
            throw new DomainException("PROJECT_PATH_NOT_FOUND", "Project path is not a directory: " + path);
        }
        Path requested;
        try {
            requested = path.toRealPath();
        } catch (IOException error) {
            throw new DomainException("PROJECT_PATH_INVALID", "Unable to resolve project path: " + path);
        }
        ProcessRunner.ProcessResult result = runGit(requested, List.of("rev-parse", "--show-toplevel"));
        if (result.exitCode() != 0) {
            throw new DomainException("PROJECT_NOT_GIT", "Project is not a Git repository: " + path);
        }
        try {
            Path root = Path.of(result.stdout().trim()).toRealPath();
            if (!requested.equals(root)) {
                throw new DomainException("PROJECT_SCOPE_MISMATCH",
                        "Canonical path must be the Git repository root: " + root);
            }
            Path effectiveDataRoot = Files.exists(dataRoot) ? dataRoot.toRealPath() : dataRoot;
            if (effectiveDataRoot.startsWith(root) || root.startsWith(effectiveDataRoot)) {
                throw new DomainException("PROJECT_DATA_ROOT_OVERLAP",
                        "Offcanon data root and canonical repository must not contain one another");
            }
            return root;
        } catch (IOException e) {
            throw new DomainException("PROJECT_PATH_INVALID", "Unable to resolve project path: " + path);
        }
    }

    private String writeWorkingTree(Path root, GitObjectStore objectStore) {
        Path index;
        try {
            index = Files.createTempFile("offcanon-index-", ".tmp");
            Files.deleteIfExists(index);
        } catch (IOException e) {
            throw new DomainException("SNAPSHOT_FAILED", "Unable to create temporary Git index");
        }
        try {
            Map<String, String> env = new HashMap<>(objectStore.environment());
            env.put("GIT_INDEX_FILE", index.toString());
            ProcessRunner.ProcessResult readTree = runGit(root, List.of("read-tree", "HEAD"), env);
            if (readTree.exitCode() != 0) {
                readTree = runGit(root, List.of("read-tree", "--empty"), env);
                if (readTree.exitCode() != 0) {
                    throw gitFailure("Unable to initialise temporary index", readTree);
                }
            }
            stageWorkingTree(root, env);
            ProcessRunner.ProcessResult tree = runGit(root, List.of("write-tree"), env);
            if (tree.exitCode() != 0 || tree.stdout().isBlank()) {
                throw gitFailure("Unable to write snapshot tree", tree);
            }
            return tree.stdout().trim();
        } finally {
            deleteQuietly(index);
        }
    }

    private String writeWorkspaceTree(Path gitRoot,
                                      Path workspace,
                                      String parentFingerprint,
                                      GitObjectStore objectStore) {
        if (parentFingerprint == null || parentFingerprint.isBlank()) {
            throw new DomainException("SNAPSHOT_PARENT_MISSING", "Workspace snapshot requires a parent fingerprint");
        }
        Path index;
        try {
            index = Files.createTempFile("offcanon-index-", ".tmp");
            Files.deleteIfExists(index);
        } catch (IOException error) {
            throw new DomainException("SNAPSHOT_FAILED", "Unable to create temporary Git index");
        }
        try {
            ProcessRunner.ProcessResult gitDirResult = runGit(gitRoot, List.of("rev-parse", "--absolute-git-dir"));
            if (gitDirResult.exitCode() != 0 || gitDirResult.stdout().isBlank()) {
                throw gitFailure("Unable to resolve Git object database", gitDirResult);
            }
            Map<String, String> env = new HashMap<>(objectStore.environment());
            env.put("GIT_INDEX_FILE", index.toString());
            env.put("GIT_DIR", gitDirResult.stdout().trim());
            env.put("GIT_WORK_TREE", workspace.toString());
            ProcessRunner.ProcessResult readTree = runGit(workspace, List.of("read-tree", parentFingerprint), env);
            if (readTree.exitCode() != 0) {
                throw gitFailure("Unable to initialise result snapshot", readTree);
            }
            stageWorkingTree(workspace, env);
            ProcessRunner.ProcessResult tree = runGit(workspace, List.of("write-tree"), env);
            if (tree.exitCode() != 0 || tree.stdout().isBlank()) {
                throw gitFailure("Unable to write result snapshot", tree);
            }
            return tree.stdout().trim();
        } finally {
            deleteQuietly(index);
        }
    }

    private GitObjectStore objectStore(Project project, Path gitRoot) {
        Path objectDirectory = dataRoot.resolve("git-objects").resolve(project.id().toString())
                .toAbsolutePath().normalize();
        try {
            ensureManagedDirectory(objectDirectory.getParent());
            if (Files.exists(objectDirectory, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(objectDirectory)
                    || !Files.isDirectory(objectDirectory, LinkOption.NOFOLLOW_LINKS)
                    || !hasNoSymbolicComponents(objectDirectory))) {
                throw new DomainException("SNAPSHOT_OBJECT_STORE_INVALID",
                        "Snapshot object storage must be a real directory");
            }
            Files.createDirectories(objectDirectory);
            if (!Files.isDirectory(objectDirectory, LinkOption.NOFOLLOW_LINKS)
                    || !hasNoSymbolicComponents(objectDirectory)) {
                throw new DomainException("SNAPSHOT_OBJECT_STORE_INVALID",
                        "Snapshot object storage must be a real directory");
            }
            ProcessRunner.ProcessResult commonDirResult = runGit(gitRoot, List.of("rev-parse", "--git-common-dir"));
            if (commonDirResult.exitCode() != 0 || commonDirResult.stdout().isBlank()) {
                throw gitFailure("Unable to resolve canonical Git common directory", commonDirResult);
            }
            Path commonDirectory = Path.of(commonDirResult.stdout().trim());
            if (!commonDirectory.isAbsolute()) commonDirectory = gitRoot.resolve(commonDirectory);
            Path canonicalObjects = commonDirectory.toRealPath().resolve("objects").toRealPath();
            return new GitObjectStore(objectDirectory, canonicalObjects);
        } catch (IOException error) {
            throw new DomainException("SNAPSHOT_FAILED",
                    "Unable to prepare isolated Git object storage: "
                            + (error.getMessage() == null ? objectDirectory : error.getMessage()));
        }
    }

    private void stageWorkingTree(Path root, Map<String, String> env) {
        List<String> addArgs = new ArrayList<>();
        // Git's core.filemode setting is commonly disabled on shared or
        // copied repositories. Offcanon tracks the executable bit explicitly,
        // so POSIX workspaces must opt into Git's mode comparison for this
        // isolated index. Providers without POSIX attributes cannot represent
        // a local chmod and retain Git's normal platform behavior.
        if (GitFileMode.supportsPosixAttributes(root)) {
            addArgs.addAll(List.of("-c", "core.filemode=true"));
        }
        addArgs.addAll(List.of("add", "-A", "--", "."));
        addArgs.addAll(List.of(
                ":(exclude,icase,glob)**/.env",
                ":(exclude,icase,glob)**/.git/**",
                ":(exclude,icase,glob)**/.offcanon/**",
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
        rejectGitlinks(root, env);
    }

    private void rejectGitlinks(Path root, Map<String, String> env) {
        ProcessRunner.ProcessResult listed = runGit(root, List.of("ls-files", "--stage"), env);
        if (listed.exitCode() != 0) {
            throw gitFailure("Unable to inspect snapshot index", listed);
        }
        for (String line : listed.stdout().split("\\R")) {
            if (line.startsWith("160000 ")) {
                String path = line.contains("\t") ? line.substring(line.indexOf('\t') + 1) : line;
                throw new DomainException("SNAPSHOT_GITLINK_UNSUPPORTED",
                        "Git submodules and nested repositories are not supported in an experiment snapshot: " + path);
            }
        }
    }

    private Set<String> gitIgnored(Project project, Path source) {
        Path gitRoot = requireGitRoot(project.canonicalPath());
        Map<String, String> environment = new HashMap<>();
        if (!source.equals(gitRoot)) {
            ProcessRunner.ProcessResult gitDir = runGit(gitRoot, List.of("rev-parse", "--absolute-git-dir"));
            if (gitDir.exitCode() != 0 || gitDir.stdout().isBlank()) {
                throw gitFailure("Unable to resolve Git metadata for ignored paths", gitDir);
            }
            environment.put("GIT_DIR", gitDir.stdout().trim());
            environment.put("GIT_WORK_TREE", source.toString());
        }
        ProcessRunner.ProcessResult ignored = runGit(source,
                List.of("ls-files", "--others", "--ignored", "--exclude-standard", "--directory"), environment);
        if (ignored.exitCode() != 0) {
            throw gitFailure("Unable to enumerate ignored snapshot paths", ignored);
        }
        if (ignored.stdout().contains("process output truncated; head/tail retained")) {
            throw new DomainException("SNAPSHOT_IGNORED_LIST_TOO_LARGE",
                    "Ignored-path metadata exceeded the capture safety limit");
        }
        Set<String> result = new HashSet<>();
        for (String line : ignored.stdout().split("\\R")) {
            String relative = line.trim().replace('\\', '/');
            if (!relative.isBlank()) result.add(relative);
        }
        return Set.copyOf(result);
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

    private List<TreeEntry> listTreeFiles(Path gitRoot,
                                          String fingerprint,
                                          Map<String, String> environment) {
        ProcessRunner.ProcessResult listed = runGit(gitRoot,
                List.of("ls-tree", "-r", "-z", "--full-tree", fingerprint), environment);
        if (listed.exitCode() != 0) {
            throw gitFailure("Unable to enumerate snapshot tree", listed);
        }
        if (listed.stdout().contains("process output truncated; head/tail retained")) {
            throw new DomainException("SNAPSHOT_TREE_TOO_LARGE",
                    "Snapshot tree path metadata exceeded the capture safety limit");
        }

        List<TreeEntry> files = new ArrayList<>();
        for (String record : listed.stdout().split("\0", -1)) {
            if (record.isEmpty()) continue;
            int separator = record.indexOf('\t');
            if (separator <= 0 || separator == record.length() - 1) {
                throw new DomainException("SNAPSHOT_INVALID_ENTRY", "Malformed entry in snapshot tree");
            }
            String[] metadata = record.substring(0, separator).split(" ");
            String relative = record.substring(separator + 1);
            if (metadata.length != 3) {
                throw new DomainException("SNAPSHOT_INVALID_ENTRY", "Malformed entry in snapshot tree: " + relative);
            }
            if ("120000".equals(metadata[0])) {
                throw new DomainException("SNAPSHOT_SYMLINK_UNSUPPORTED", "Symlink is not supported: " + relative);
            }
            if ("160000".equals(metadata[0])) {
                throw new DomainException("SNAPSHOT_GITLINK_UNSUPPORTED",
                        "Git submodules are not supported in an experiment snapshot: " + relative);
            }
            int mode = switch (metadata[0]) {
                case "100644" -> GitFileMode.REGULAR;
                case "100755" -> GitFileMode.EXECUTABLE;
                default -> -1;
            };
            if (!"blob".equals(metadata[1]) || mode < 0) {
                throw new DomainException("SNAPSHOT_INVALID_ENTRY", "Unsupported snapshot tree entry: " + relative);
            }
            validateTreePath(relative);
            if (exclusionReason(relative) != null) {
                throw new DomainException("SNAPSHOT_PROTECTED_ENTRY",
                        "Protected path remained in snapshot tree: " + relative);
            }
            files.add(new TreeEntry(relative, mode));
        }
        return List.copyOf(files);
    }

    private void collectExcluded(Path root,
                                 List<Snapshot.ExcludedPath> excluded,
                                 Set<String> ignored) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                if (directory.equals(root)) return FileVisitResult.CONTINUE;
                String relative = root.relativize(directory).toString().replace('\\', '/');
                if (Files.isSymbolicLink(directory)) {
                    throw new DomainException("SNAPSHOT_SYMLINK_UNSUPPORTED", "Symlink directory is not supported: " + relative);
                }
                String reason = exclusionReason(relative, ignored);
                if (reason != null) {
                    excluded.add(new Snapshot.ExcludedPath(relative, reason));
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (Files.isSymbolicLink(file)) {
                    throw new DomainException("SNAPSHOT_SYMLINK_UNSUPPORTED", "Symlink is not supported: " + relative);
                }
                String reason = exclusionReason(relative, ignored);
                if (reason != null) excluded.add(new Snapshot.ExcludedPath(relative, reason));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void materializeTreeFiles(Path source,
                                      Path destination,
                                      List<TreeEntry> treeFiles,
                                      List<String> included) throws IOException {
        if (Files.isSymbolicLink(destination)
                || !Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)
                || !hasNoSymbolicComponents(destination)) {
            throw new DomainException("SNAPSHOT_DESTINATION_INVALID",
                    "Snapshot destination must be a real directory");
        }
        Path realSource = source.toRealPath();
        Path realDestination = destination.toRealPath();
        if (!realDestination.equals(destination.toAbsolutePath().normalize())) {
            throw new DomainException("SNAPSHOT_PATH_ESCAPE",
                    "Snapshot destination resolves outside its managed path");
        }
        for (TreeEntry entry : treeFiles) {
            String relative = entry.relative();
            Path original = source.resolve(relative).normalize();
            Path target = destination.resolve(relative).normalize();
            if (!original.startsWith(source) || !target.startsWith(destination)
                    || !Files.isRegularFile(original, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new DomainException("SNAPSHOT_SOURCE_CHANGED", "Snapshot source changed while copying: " + relative);
            }
            if (!original.toRealPath().startsWith(realSource)) {
                throw new DomainException("SNAPSHOT_PATH_ESCAPE", "Snapshot source path escapes workspace: " + relative);
            }
            validateSnapshotFile(original, relative);
            createSafeDirectories(target.getParent(), destination);
            if (!target.getParent().toRealPath().startsWith(realDestination)
                    || (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(target) || !target.toRealPath().startsWith(realDestination)))) {
                throw new DomainException("SNAPSHOT_PATH_ESCAPE", "Snapshot target escapes destination: " + relative);
            }
            Files.copy(original, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            if (Files.isSymbolicLink(target)
                    || !target.toRealPath().startsWith(realDestination)) {
                throw new DomainException("SNAPSHOT_PATH_ESCAPE",
                        "Snapshot target resolves outside destination: " + relative);
            }
            // COPY_ATTRIBUTES is provider-dependent; apply the Git bit
            // explicitly so the materialized tree agrees with its tree hash.
            GitFileMode.apply(target, entry.mode());
            included.add(relative);
        }
    }

    private void validateTreePath(String relative) {
        if (relative.isBlank() || relative.indexOf('\\') >= 0) {
            throw new DomainException("SNAPSHOT_INVALID_ENTRY", "Invalid snapshot tree path: " + relative);
        }
        Path path;
        try {
            path = Path.of(relative);
        } catch (RuntimeException error) {
            throw new DomainException("SNAPSHOT_INVALID_ENTRY", "Invalid snapshot tree path: " + relative);
        }
        if (path.isAbsolute() || path.normalize().startsWith("..") || !path.normalize().toString().equals(path.toString())) {
            throw new DomainException("SNAPSHOT_INVALID_ENTRY", "Snapshot tree path escapes destination: " + relative);
        }
    }

    private String rawFingerprint(Path root, Set<String> ignored) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> files = new ArrayList<>();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                    if (directory.equals(root)) return FileVisitResult.CONTINUE;
                    String relative = root.relativize(directory).toString().replace('\\', '/');
                    if (Files.isSymbolicLink(directory)) {
                        throw new DomainException("SNAPSHOT_SYMLINK_UNSUPPORTED", "Symlink directory is not supported: " + relative);
                    }
                    return exclusionReason(relative, ignored) == null
                            ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    if (Files.isSymbolicLink(file)) {
                        throw new DomainException("SNAPSHOT_SYMLINK_UNSUPPORTED", "Symlink is not supported: " + relative);
                    }
                    if (!isExcludedPath(root, file, ignored)) files.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
            files.sort(java.util.Comparator.naturalOrder());
            for (Path path : files) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (Files.isSymbolicLink(path)) {
                    throw new DomainException("SNAPSHOT_SYMLINK_UNSUPPORTED", "Symlink is not supported: " + relative);
                }
                digest.update(relative.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
                if (Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    validateSnapshotFile(path, relative);
                    try (InputStream input = Files.newInputStream(path)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
                    }
                }
                digest.update((byte) 0);
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        } catch (IOException error) {
            throw new DomainException("SNAPSHOT_FAILED", "Unable to fingerprint workspace");
        }
    }

    private boolean isExcludedPath(Path root, Path path, Set<String> ignored) {
        String relative = root.relativize(path).toString().replace('\\', '/');
        return exclusionReason(relative, ignored) != null;
    }

    private String exclusionReason(String relative, Set<String> ignored) {
        String hardcoded = exclusionReason(relative);
        if (hardcoded != null) return hardcoded;
        String normalized = relative.replace('\\', '/');
        for (String entry : ignored) {
            String ignoredPath = entry.endsWith("/") ? entry.substring(0, entry.length() - 1) : entry;
            if (normalized.equals(ignoredPath) || normalized.startsWith(ignoredPath + "/")) {
                return "git ignored";
            }
        }
        return null;
    }

    private void validateSnapshotFile(Path file, String relative) throws IOException {
        long size = Files.size(file);
        if (size > MAX_SNAPSHOT_FILE_BYTES) {
            throw new DomainException("SNAPSHOT_FILE_TOO_LARGE",
                    "Snapshot file exceeds 20 MiB safety limit: " + relative);
        }
        if (size <= 1024) {
            byte[] prefix;
            try (InputStream input = Files.newInputStream(file)) {
                prefix = input.readNBytes(256);
            }
            String text = new String(prefix, java.nio.charset.StandardCharsets.UTF_8);
            if (text.startsWith(LFS_POINTER_HEADER)) {
                throw new DomainException("SNAPSHOT_LFS_UNSUPPORTED",
                        "Git LFS pointer requires explicit materialization policy: " + relative);
            }
        }
    }

    private String exclusionReason(String relative) {
        String[] parts = relative.toLowerCase(java.util.Locale.ROOT).split("/");
        for (String part : parts) {
            if (part.equals(".git") || part.equals(".offcanon") || part.equals("node_modules")
                    || part.equals("target") || part.equals("build") || part.equals("dist")
                    || part.equals(".idea") || part.equals(".vscode")) {
                return "runtime or dependency directory";
            }
        }
        if (SensitivePathPolicy.isSensitiveRelativePath(relative)) {
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
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(path) || !hasNoSymbolicComponents(path)) {
            throw new IOException("Refusing to delete a symbolic-link snapshot path");
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

    private void ensureManagedDirectory(Path directory) throws IOException {
        if (directory == null || !hasNoSymbolicComponents(directory)) {
            throw new DomainException("SNAPSHOT_DESTINATION_INVALID",
                    "Snapshot runtime directory must not contain symbolic links");
        }
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !hasNoSymbolicComponents(directory)) {
            throw new DomainException("SNAPSHOT_DESTINATION_INVALID",
                    "Snapshot runtime directory must be a real directory");
        }
    }

    private void createSafeDirectories(Path directory, Path root) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = directory.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot)
                || !hasNoSymbolicComponents(normalizedRoot)) {
            throw new DomainException("SNAPSHOT_PATH_ESCAPE",
                    "Snapshot target escapes destination");
        }
        Path current = normalizedRoot;
        Path relative = normalizedRoot.relativize(normalized);
        for (Path part : relative) {
            current = current.resolve(part.toString());
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)
                        || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new DomainException("SNAPSHOT_PATH_ESCAPE",
                            "Snapshot target contains an invalid directory component");
                }
            } else {
                Files.createDirectory(current);
            }
        }
    }

    private boolean hasNoSymbolicComponents(Path path) {
        Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(current)) {
                return false;
            }
            current = current.getParent();
        }
        return true;
    }

    private record GitObjectStore(Path objectDirectory, Path canonicalObjects) {
        private Map<String, String> environment() {
            return Map.of(
                    "GIT_OBJECT_DIRECTORY", objectDirectory.toString(),
                    "GIT_ALTERNATE_OBJECT_DIRECTORIES", canonicalObjects.toString());
        }
    }

    private record TreeEntry(String relative, int mode) {
    }
}
