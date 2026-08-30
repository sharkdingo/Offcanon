package com.offcanon.infrastructure.agent;

import com.offcanon.agent.domain.ToolDefinition;
import com.offcanon.agent.domain.ToolResult;
import com.offcanon.experiment.domain.Experiment;
import com.offcanon.port.Tool;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.shared.domain.SensitivePathPolicy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;

@Component
public class ListFilesTool implements Tool {
    private static final int MAX_FILES = 500;
    /** Covers conventional source roots while keeping accidental deep trees bounded. */
    private static final int MAX_DEPTH = 16;
    private static final String DEPTH_LIMIT_MARKER = "...[directory depth limit reached]";
    private static final String SENSITIVE_LIMIT_MARKER = "...[sensitive files omitted]";
    private final WorkspacePathResolver paths;

    public ListFilesTool(WorkspacePathResolver paths) {
        this.paths = paths;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("list_files", "List files below a directory in the experiment workspace.", Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string")),
                "required", java.util.List.of("path")));
    }

    @Override
    public ToolResult execute(Experiment experiment, String callId, Map<String, Object> arguments) {
        String requested = ToolArguments.requiredString(arguments, "path");
        Path path = paths.resolve(experiment, requested, false);
        if (!Files.isDirectory(path)) {
            return ToolResult.failure(callId, definition().name(), "Not a directory: " + requested);
        }
        try {
            PriorityQueue<String> smallest = new PriorityQueue<>(MAX_FILES, Comparator.reverseOrder());
            int[] seen = {0};
            boolean[] depthLimited = {false};
            boolean[] sensitiveOmitted = {false};
            // Walk one level beyond the public bound so the visitor can emit an explicit marker.
            Files.walkFileTree(path, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH + 1, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                    checkInterrupted();
                    String directoryRelative = path.relativize(directory).toString().replace('\\', '/');
                    if (!directory.equals(path) && SensitivePathPolicy.isSensitiveRelativePath(directoryRelative)) {
                        sensitiveOmitted[0] = true;
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!directory.equals(path) && isRuntimeDirectory(directory)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (path.relativize(directory).getNameCount() >= MAX_DEPTH) {
                        depthLimited[0] = true;
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    checkInterrupted();
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return FileVisitResult.CONTINUE;
                    String relative = path.relativize(file).toString().replace('\\', '/');
                    if (SensitivePathPolicy.isSensitiveRelativePath(relative)) {
                        sensitiveOmitted[0] = true;
                        return FileVisitResult.CONTINUE;
                    }
                    seen[0]++;
                    if (smallest.size() < MAX_FILES) {
                        smallest.add(relative);
                    } else if (relative.compareTo(smallest.peek()) < 0) {
                        smallest.poll();
                        smallest.add(relative);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            ArrayList<String> files = new ArrayList<>(smallest);
            files.sort(Comparator.naturalOrder());
            if (seen[0] > MAX_FILES) files.add("...[file limit reached]");
            if (depthLimited[0]) files.add(DEPTH_LIMIT_MARKER);
            if (sensitiveOmitted[0]) files.add(SENSITIVE_LIMIT_MARKER);
            return ToolResult.success(callId, definition().name(), String.join("\n", files));
        } catch (IOException error) {
            return ToolResult.failure(callId, definition().name(), "Unable to list " + requested + ": " + error.getMessage());
        }
    }

    private void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new DomainException("TOOL_INTERRUPTED", "File listing was interrupted");
        }
    }

    private boolean isRuntimeDirectory(Path directory) {
        String name = directory.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.equals(".git") || name.equals(".offcanon") || name.equals("node_modules")
                || name.equals("target") || name.equals("build") || name.equals("dist")
                || name.equals(".idea") || name.equals(".vscode");
    }
}
