package com.pico.infrastructure.agent;

import com.pico.agent.domain.ToolDefinition;
import com.pico.agent.domain.ToolResult;
import com.pico.experiment.domain.Experiment;
import com.pico.port.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;

@Component
public class ListFilesTool implements Tool {
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
            ArrayList<String> files = new ArrayList<>();
            Files.walk(path, 3).filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).forEach(file -> files.add(path.relativize(file).toString().replace('\\', '/')));
            return ToolResult.success(callId, definition().name(), String.join("\n", files));
        } catch (IOException error) {
            return ToolResult.failure(callId, definition().name(), "Unable to list " + requested + ": " + error.getMessage());
        }
    }
}
