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
import java.util.Map;

@Component
public class ReadFileTool implements Tool {
    private static final int MAX_CHARS = 30_000;
    private final WorkspacePathResolver paths;

    public ReadFileTool(WorkspacePathResolver paths) {
        this.paths = paths;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("read_file", "Read a UTF-8 text file inside the experiment workspace.", Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string")),
                "required", java.util.List.of("path")));
    }

    @Override
    public ToolResult execute(Experiment experiment, String callId, Map<String, Object> arguments) {
        String requested = ToolArguments.requiredString(arguments, "path");
        Path path = paths.resolve(experiment, requested, false);
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (containsZeroByte(bytes)) {
                return ToolResult.failure(callId, definition().name(), "Binary files are not readable as text");
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            if (content.length() > MAX_CHARS) {
                content = content.substring(0, MAX_CHARS) + "\n...[truncated]";
            }
            return ToolResult.success(callId, definition().name(), content);
        } catch (IOException error) {
            return ToolResult.failure(callId, definition().name(), "Unable to read " + requested + ": " + error.getMessage());
        }
    }

    private boolean containsZeroByte(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) return true;
        }
        return false;
    }
}
