package com.pico.infrastructure.agent;

import com.pico.agent.domain.ToolDefinition;
import com.pico.agent.domain.ToolResult;
import com.pico.experiment.domain.Experiment;
import com.pico.port.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

@Component
public class WriteFileTool implements Tool {
    private static final int MAX_CHARS = 200_000;
    private final WorkspacePathResolver paths;

    public WriteFileTool(WorkspacePathResolver paths) {
        this.paths = paths;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("write_file", "Atomically replace a UTF-8 text file inside the experiment workspace.", Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string"),
                        "content", Map.of("type", "string")),
                "required", java.util.List.of("path", "content")));
    }

    @Override
    public ToolResult execute(Experiment experiment, String callId, Map<String, Object> arguments) {
        String requested = ToolArguments.requiredString(arguments, "path");
        String content = ToolArguments.requiredString(arguments, "content");
        if (content.length() > MAX_CHARS) {
            return ToolResult.failure(callId, definition().name(), "File content exceeds " + MAX_CHARS + " characters");
        }
        Path path = paths.resolve(experiment, requested, true);
        try {
            Files.createDirectories(path.getParent());
            Path temporary = Files.createTempFile(path.getParent(), ".pico-write-", ".tmp");
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return ToolResult.success(callId, definition().name(), "Wrote " + requested);
        } catch (IOException error) {
            return ToolResult.failure(callId, definition().name(), "Unable to write " + requested + ": " + error.getMessage());
        }
    }
}
