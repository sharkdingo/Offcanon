package com.offcanon.infrastructure.agent;

import com.offcanon.agent.domain.ToolDefinition;
import com.offcanon.agent.domain.ToolResult;
import com.offcanon.experiment.domain.Experiment;
import com.offcanon.port.Tool;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.shared.domain.SensitivePathPolicy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;

@Component
public class SearchFilesTool implements Tool {
    private static final long MAX_FILE_BYTES = 512_000;
    private static final int MAX_MATCHES = 200;
    private static final int MAX_LINE_CHARS = 500;
    /** Covers conventional source roots while keeping accidental deep trees bounded. */
    private static final int MAX_DEPTH = 16;
    private static final String DEPTH_LIMIT_MARKER = "...[directory depth limit reached]";
    private static final String UNREADABLE_MARKER = "...[unreadable files skipped]";
    private static final String LARGE_FILE_MARKER = "...[files larger than 512000 bytes skipped]";
    private static final String SENSITIVE_FILE_MARKER = "...[sensitive files omitted]";
    private final WorkspacePathResolver paths;

    public SearchFilesTool(WorkspacePathResolver paths) {
        this.paths = paths;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("search_files", "Search UTF-8 text files below a directory.", Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string"),
                        "query", Map.of("type", "string")),
                "required", java.util.List.of("path", "query")));
    }

    @Override
    public ToolResult execute(Experiment experiment, String callId, Map<String, Object> arguments) {
        String requested = ToolArguments.requiredString(arguments, "path");
        String query = ToolArguments.requiredString(arguments, "query");
        Path root = paths.resolve(experiment, requested, false);
        if (!Files.isDirectory(root)) {
            return ToolResult.failure(callId, definition().name(), "Not a directory: " + requested);
        }
        ArrayList<String> matches = new ArrayList<>();
        try {
            Path realRoot = root.toRealPath();
            boolean[] depthLimited = {false};
            boolean[] unreadable = {false};
            boolean[] largeFileSkipped = {false};
            boolean[] sensitiveOmitted = {false};
            // Walk one level beyond the public bound so the visitor can emit an explicit marker.
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH + 1, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                    checkInterrupted();
                    String directoryRelative = root.relativize(directory).toString().replace('\\', '/');
                    if (!directory.equals(root) && SensitivePathPolicy.isSensitiveRelativePath(directoryRelative)) {
                        sensitiveOmitted[0] = true;
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!directory.equals(root) && isRuntimeDirectory(directory)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (root.relativize(directory).getNameCount() >= MAX_DEPTH) {
                        depthLimited[0] = true;
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    checkInterrupted();
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return FileVisitResult.CONTINUE;
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    if (SensitivePathPolicy.isSensitiveRelativePath(relative)) {
                        sensitiveOmitted[0] = true;
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        if (!file.toRealPath().startsWith(realRoot)) return FileVisitResult.CONTINUE;
                        // Bound the read itself. A separate size check can be
                        // invalidated by a concurrent file replacement or
                        // growth before readAllBytes would allocate.
                        byte[] bytes;
                        try (InputStream input = Files.newInputStream(file)) {
                            bytes = input.readNBytes(Math.toIntExact(MAX_FILE_BYTES) + 1);
                        }
                        if (bytes.length > MAX_FILE_BYTES) {
                            // A successful search with no matches must not be
                            // mistaken for proof that every file was checked.
                            // Keep the result successful, but disclose the
                            // bounded omission to the model.
                            largeFileSkipped[0] = true;
                            return FileVisitResult.CONTINUE;
                        }
                        String content = Utf8Text.decode(bytes);
                        int lineNumber = 0;
                        for (String line : content.split("\\R", -1)) {
                            lineNumber++;
                            if (line.contains(query)) {
                                String compact = line.trim();
                                if (compact.length() > MAX_LINE_CHARS) compact = compact.substring(0, MAX_LINE_CHARS) + "...";
                                matches.add(relative + ":" + lineNumber + ": " + compact);
                                if (matches.size() >= MAX_MATCHES) return FileVisitResult.TERMINATE;
                            }
                        }
                    } catch (IOException ignored) {
                        // A single unreadable file should not abort the search,
                        // but the result must disclose that it is incomplete.
                        unreadable[0] = true;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    checkInterrupted();
                    unreadable[0] = true;
                    return FileVisitResult.CONTINUE;
                }
            });
            if (matches.size() >= MAX_MATCHES) matches.add("...[match limit reached]");
            if (unreadable[0]) matches.add(UNREADABLE_MARKER);
            if (largeFileSkipped[0]) matches.add(LARGE_FILE_MARKER);
            if (sensitiveOmitted[0]) matches.add(SENSITIVE_FILE_MARKER);
            if (depthLimited[0]) matches.add(DEPTH_LIMIT_MARKER);
            return ToolResult.success(callId, definition().name(), String.join("\n", matches));
        } catch (IOException error) {
            return ToolResult.failure(callId, definition().name(), "Unable to search " + requested + ": " + error.getMessage());
        }
    }

    private void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new DomainException("TOOL_INTERRUPTED", "File search was interrupted");
        }
    }

    private boolean isRuntimeDirectory(Path directory) {
        String name = directory.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.equals(".git") || name.equals(".offcanon") || name.equals("node_modules")
                || name.equals("target") || name.equals("build") || name.equals("dist")
                || name.equals(".idea") || name.equals(".vscode");
    }
}
