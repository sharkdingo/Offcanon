package com.pico.infrastructure.agent;

import com.pico.agent.domain.ToolDefinition;
import com.pico.agent.domain.ToolResult;
import com.pico.experiment.domain.Experiment;
import com.pico.port.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

@Component
public class SearchFilesTool implements Tool {
    private static final long MAX_FILE_BYTES = 512_000;
    private static final int MAX_MATCHES = 200;
    private static final int MAX_LINE_CHARS = 500;
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
            try (var files = Files.walk(root, 6)) {
                files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).forEach(file -> {
                    if (matches.size() >= MAX_MATCHES) return;
                    try {
                        if (!file.toRealPath().startsWith(realRoot)) return;
                        if (Files.size(file) > MAX_FILE_BYTES) return;
                        byte[] bytes = Files.readAllBytes(file);
                        String content = Utf8Text.decode(bytes);
                        int lineNumber = 0;
                        for (String line : content.split("\\R", -1)) {
                            lineNumber++;
                            if (line.contains(query)) {
                                String compact = line.trim();
                                if (compact.length() > MAX_LINE_CHARS) compact = compact.substring(0, MAX_LINE_CHARS) + "...";
                                matches.add(root.relativize(file).toString().replace('\\', '/') + ":" + lineNumber + ": " + compact);
                                if (matches.size() >= MAX_MATCHES) return;
                            }
                        }
                    } catch (IOException ignored) {
                        // A single unreadable file should not abort the search.
                    }
                });
            }
            if (matches.size() >= MAX_MATCHES) matches.add("...[match limit reached]");
            return ToolResult.success(callId, definition().name(), String.join("\n", matches));
        } catch (IOException error) {
            return ToolResult.failure(callId, definition().name(), "Unable to search " + requested + ": " + error.getMessage());
        }
    }
}
