package com.offcanon.infrastructure.agent;

import com.offcanon.agent.domain.ToolDefinition;
import com.offcanon.agent.domain.ToolResult;
import com.offcanon.experiment.domain.Experiment;
import com.offcanon.port.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

@Component
public class DeleteFileTool implements Tool {
    private final WorkspacePathResolver paths;

    public DeleteFileTool(WorkspacePathResolver paths) {
        this.paths = paths;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("delete_file",
                "Delete one regular file inside the experiment workspace.", Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string")),
                "required", java.util.List.of("path")));
    }

    @Override
    public ToolResult execute(Experiment experiment, String callId, Map<String, Object> arguments) {
        String requested = ToolArguments.requiredString(arguments, "path");
        Path path = paths.resolve(experiment, requested, true);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return ToolResult.failure(callId, definition().name(), "File does not exist: " + requested);
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return ToolResult.failure(callId, definition().name(), "Delete target is not a regular file: " + requested);
        }
        try {
            Files.delete(path);
            return ToolResult.success(callId, definition().name(), "Deleted " + requested);
        } catch (IOException error) {
            return ToolResult.failure(callId, definition().name(),
                    "Unable to delete " + requested + ": " + error.getMessage());
        }
    }
}
