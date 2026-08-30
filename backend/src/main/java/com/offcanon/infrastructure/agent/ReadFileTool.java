package com.offcanon.infrastructure.agent;

import com.offcanon.agent.domain.ToolDefinition;
import com.offcanon.agent.domain.ToolResult;
import com.offcanon.experiment.domain.Experiment;
import com.offcanon.port.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
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
            // Read one byte past the limit instead of relying on a separate
            // size check. The file may be replaced or grow between metadata
            // inspection and opening it; the bounded read keeps the tool's
            // memory use deterministic in that race.
            byte[] bytes;
            try (InputStream input = Files.newInputStream(path)) {
                bytes = input.readNBytes(Math.toIntExact(MAX_BYTES) + 1);
            }
            if (bytes.length > MAX_BYTES) {
                return ToolResult.failure(callId, definition().name(), "File is too large to read safely (limit " + MAX_BYTES + " bytes)");
            }
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
