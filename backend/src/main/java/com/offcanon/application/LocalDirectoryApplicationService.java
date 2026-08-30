package com.offcanon.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offcanon.infrastructure.process.ProcessRunner;
import com.offcanon.shared.domain.DomainException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lists local directories for the project registration flow.
 *
 * The browser deliberately exposes directory metadata only. Project creation
 * still uses ProjectApplicationService and the canonical Git-root validation
 * as its single source of truth.
 */
@Service
public class LocalDirectoryApplicationService {
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_DIRECTORY_ENTRIES = 500;

    private final ProcessRunner processRunner;
    private final ObjectMapper objectMapper;
    private final Path homeDirectory;
    private final Path workingDirectory;

    @Autowired
    public LocalDirectoryApplicationService(ProcessRunner processRunner, ObjectMapper objectMapper) {
        this(processRunner, objectMapper,
                Path.of(System.getProperty("user.home", ".")),
                Path.of(System.getProperty("user.dir", ".")));
    }

    LocalDirectoryApplicationService(ProcessRunner processRunner,
                                     ObjectMapper objectMapper,
                                     Path homeDirectory,
                                     Path workingDirectory) {
        this.processRunner = processRunner;
        this.objectMapper = objectMapper;
        this.homeDirectory = homeDirectory.toAbsolutePath().normalize();
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
    }

    public DirectoryListing browse(String requestedPath) {
        Path directory = resolveDirectory(requestedPath);
        Path gitRoot = detectGitRoot(directory);
        DirectoryChildren children = listDirectories(directory);
        return new DirectoryListing(
                directory,
                parent(directory),
                children.entries(),
                children.truncated(),
                gitRoot,
                suggestedName(gitRoot),
                suggestedVerificationCommands(gitRoot),
                locations());
    }

    private Path resolveDirectory(String requestedPath) {
        Path requested;
        try {
            requested = requestedPath == null || requestedPath.isBlank()
                    ? workingDirectory
                    : Path.of(requestedPath.trim());
        } catch (RuntimeException error) {
            throw new DomainException("DIRECTORY_PATH_INVALID", "Unable to parse the requested directory");
        }
        if (!requested.isAbsolute()) {
            throw new DomainException("DIRECTORY_PATH_ABSOLUTE_REQUIRED",
                    "Directory browsing requires an absolute path");
        }
        try {
            if (!Files.exists(requested, LinkOption.NOFOLLOW_LINKS)) {
                throw new DomainException("DIRECTORY_NOT_FOUND", "Directory does not exist: " + requested);
            }
            if (!Files.isDirectory(requested)) {
                throw new DomainException("DIRECTORY_NOT_A_DIRECTORY", "Path is not a directory: " + requested);
            }
            Path resolved = requested.toRealPath();
            if (!Files.isReadable(resolved)) {
                throw new DomainException("DIRECTORY_NOT_READABLE", "Directory cannot be read: " + resolved);
            }
            return resolved;
        } catch (DomainException error) {
            throw error;
        } catch (SecurityException error) {
            throw new DomainException("DIRECTORY_NOT_READABLE", "Directory cannot be read: " + requested);
        } catch (IOException error) {
            throw new DomainException("DIRECTORY_PATH_INVALID", "Unable to resolve directory: " + requested);
        }
    }

    private DirectoryChildren listDirectories(Path directory) {
        List<DirectoryEntry> entries = new ArrayList<>();
        boolean truncated = false;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                if (isInternalDirectory(child)) continue;
                try {
                    if (!Files.isDirectory(child)) continue;
                    if (entries.size() >= MAX_DIRECTORY_ENTRIES) {
                        truncated = true;
                        break;
                    }
                    Path resolved = child.toRealPath();
                    entries.add(new DirectoryEntry(child.getFileName().toString(), resolved));
                } catch (IOException | SecurityException ignored) {
                    // A directory may disappear or become unreadable while the listing is built.
                }
            }
        } catch (IOException | SecurityException error) {
            throw new DomainException("DIRECTORY_LIST_FAILED", "Unable to list directory: " + directory);
        }
        entries.sort(Comparator.comparing(DirectoryEntry::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(DirectoryEntry::name));
        return new DirectoryChildren(entries, truncated);
    }

    private boolean isInternalDirectory(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.equals(".git") || name.equals(".offcanon") || name.equals("node_modules")
                || name.equals("target") || name.equals("build") || name.equals("dist")
                || name.equals(".idea") || name.equals(".vscode");
    }

    private Path detectGitRoot(Path directory) {
        ProcessRunner.ProcessResult result;
        try {
            result = processRunner.run(
                    List.of("git", "-C", directory.toString(), "rev-parse", "--show-toplevel"),
                    directory,
                    Map.of(),
                    GIT_TIMEOUT);
        } catch (RuntimeException error) {
            // Git is optional for browsing; registration will report the precise validation error.
            return null;
        }
        if (result.exitCode() != 0 || result.timedOut() || result.stdout().isBlank()) return null;
        try {
            Path root = Path.of(result.stdout().trim()).toRealPath();
            return Files.isDirectory(root) ? root : null;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    private String suggestedName(Path gitRoot) {
        if (gitRoot == null) return null;
        Path fileName = gitRoot.getFileName();
        return fileName == null || fileName.toString().isBlank() ? gitRoot.toString() : fileName.toString();
    }

    private List<String> suggestedVerificationCommands(Path gitRoot) {
        if (gitRoot == null) return List.of();
        List<String> commands = new ArrayList<>();
        if (Files.isRegularFile(gitRoot.resolve("pom.xml"))) {
            commands.add(wrapperCommand(gitRoot, "mvnw", "mvnw.cmd", "mvn") + " test");
        }
        if (Files.isRegularFile(gitRoot.resolve("build.gradle"))
                || Files.isRegularFile(gitRoot.resolve("build.gradle.kts"))) {
            commands.add(wrapperCommand(gitRoot, "gradlew", "gradlew.bat", "gradle") + " test");
        }
        if (hasNodeTestScript(gitRoot.resolve("package.json"))) {
            String runner = Files.isRegularFile(gitRoot.resolve("pnpm-lock.yaml")) ? "pnpm"
                    : Files.isRegularFile(gitRoot.resolve("yarn.lock")) ? "yarn" : "npm";
            commands.add(runner + " test");
        }
        if (Files.isRegularFile(gitRoot.resolve("go.mod"))) commands.add("go test ./...");
        if (Files.isRegularFile(gitRoot.resolve("Cargo.toml"))) commands.add("cargo test");
        return List.copyOf(commands);
    }

    private String wrapperCommand(Path root, String unixName, String windowsName, String fallback) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (windows && Files.isRegularFile(root.resolve(windowsName))) return windowsName;
        if (!windows && Files.isRegularFile(root.resolve(unixName))) return "./" + unixName;
        return fallback;
    }

    private boolean hasNodeTestScript(Path packageJson) {
        try {
            if (!Files.isRegularFile(packageJson) || Files.size(packageJson) > 1_000_000) return false;
            JsonNode scripts = objectMapper.readTree(packageJson.toFile()).path("scripts");
            return scripts.isObject() && scripts.hasNonNull("test")
                    && !scripts.path("test").asText("").isBlank();
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    private List<DirectoryLocation> locations() {
        Map<String, DirectoryLocation> unique = new LinkedHashMap<>();
        addLocation(unique, "HOME", homeDirectory);
        addLocation(unique, "WORKING_DIRECTORY", workingDirectory);
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            addLocation(unique, "FILESYSTEM_ROOT", root);
        }
        return List.copyOf(unique.values());
    }

    private void addLocation(Map<String, DirectoryLocation> locations, String kind, Path path) {
        try {
            if (Files.isDirectory(path)) {
                Path resolved = path.toRealPath();
                locations.putIfAbsent(resolved.toString(), new DirectoryLocation(kind, resolved));
            }
        } catch (IOException | SecurityException ignored) {
            // Locations are convenience shortcuts; an unavailable one is simply omitted.
        }
    }

    private Path parent(Path path) {
        Path parent = path.getParent();
        return parent == null || parent.equals(path) ? null : parent;
    }

    public record DirectoryListing(Path path,
                                   Path parent,
                                   List<DirectoryEntry> entries,
                                   boolean truncated,
                                   Path gitRoot,
                                   String suggestedName,
                                   List<String> suggestedVerificationCommands,
                                   List<DirectoryLocation> locations) {
        public DirectoryListing {
            entries = List.copyOf(entries);
            suggestedVerificationCommands = List.copyOf(suggestedVerificationCommands);
            locations = List.copyOf(locations);
        }
    }

    public record DirectoryEntry(String name, Path path) {
    }

    private record DirectoryChildren(List<DirectoryEntry> entries, boolean truncated) {
        private DirectoryChildren {
            entries = List.copyOf(entries);
        }
    }

    public record DirectoryLocation(String kind, Path path) {
    }
}
