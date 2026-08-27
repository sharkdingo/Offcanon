package com.offcanon.infrastructure.agent;

import com.offcanon.agent.domain.ToolDefinition;
import com.offcanon.agent.domain.ToolResult;
import com.offcanon.experiment.domain.Experiment;
import com.offcanon.port.Tool;
import com.offcanon.shared.domain.DomainException;
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
            Files.walkFileTree(path, EnumSet.noneOf(FileVisitOption.class), 3, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                    checkInterrupted();
                    if (!directory.equals(path) && isRuntimeDirectory(directory)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    checkInterrupted();
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return FileVisitResult.CONTINUE;
                    seen[0]++;
                    String relative = path.relativize(file).toString().replace('\\', '/');
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
