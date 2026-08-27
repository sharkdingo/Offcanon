package com.pico.infrastructure.agent;

import com.pico.agent.domain.ToolDefinition;
import com.pico.agent.domain.ToolResult;
import com.pico.experiment.domain.Experiment;
import com.pico.port.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

@Component
public class SearchFilesTool implements Tool {
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
            Files.walk(root, 6).filter(Files::isRegularFile).forEach(file -> {
                try {
                    byte[] bytes = Files.readAllBytes(file);
                    if (containsZeroByte(bytes)) return;
                    String content = new String(bytes, StandardCharsets.UTF_8);
                    int lineNumber = 0;
                    for (String line : content.split("\\R", -1)) {
                        lineNumber++;
                        if (line.contains(query)) {
                            matches.add(root.relativize(file).toString().replace('\\', '/') + ":" + lineNumber + ": " + line.trim());
                        }
                    }
                } catch (IOException ignored) {
                    // A single unreadable file should not abort the search.
                }
            });
            return ToolResult.success(callId, definition().name(), String.join("\n", matches));
        } catch (IOException error) {
            return ToolResult.failure(callId, definition().name(), "Unable to search " + requested + ": " + error.getMessage());
        }
    }

    private boolean containsZeroByte(byte[] bytes) {
        for (byte value : bytes) if (value == 0) return true;
        return false;
    }
}
