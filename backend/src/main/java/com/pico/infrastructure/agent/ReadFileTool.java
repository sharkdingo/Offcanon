package com.pico.infrastructure.agent;

import com.pico.agent.domain.ToolDefinition;
import com.pico.agent.domain.ToolResult;
import com.pico.experiment.domain.Experiment;
import com.pico.port.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Component
public class ReadFileTool implements Tool {
    private static final int MAX_CHARS = 30_000;
    private static final long MAX_BYTES = 256_000;
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
            if (Files.size(path) > MAX_BYTES) {
                return ToolResult.failure(callId, definition().name(), "File is too large to read safely (limit " + MAX_BYTES + " bytes)");
            }
            byte[] bytes = Files.readAllBytes(path);
            String content = Utf8Text.decode(bytes);
            if (content.length() > MAX_CHARS) {
                content = content.substring(0, MAX_CHARS) + "\n...[truncated]";
            }
            return ToolResult.success(callId, definition().name(), content);
        } catch (CharacterCodingException error) {
            return ToolResult.failure(callId, definition().name(), "Binary or invalid UTF-8 files are not readable as text");
        } catch (IOException error) {
            return ToolResult.failure(callId, definition().name(), "Unable to read " + requested + ": " + error.getMessage());
        }
    }

}
