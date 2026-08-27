package com.pico.infrastructure.agent;

import com.pico.agent.domain.ToolDefinition;
import com.pico.agent.domain.ToolResult;
import com.pico.experiment.domain.Experiment;
import com.pico.port.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

@Component
public class WriteFileTool implements Tool {
    private static final int MAX_CHARS = 200_000;
    private static final long MAX_EXISTING_BYTES = 512_000;
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
        Path temporary = null;
        try {
            if (Files.exists(path)) {
                if (Files.size(path) > MAX_EXISTING_BYTES) {
                    return ToolResult.failure(callId, definition().name(), "Existing file is too large to replace as text");
                }
                Utf8Text.decode(Files.readAllBytes(path));
            }
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), ".pico-write-", ".tmp");
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return ToolResult.success(callId, definition().name(), "Wrote " + requested);
        } catch (CharacterCodingException error) {
            return ToolResult.failure(callId, definition().name(), "Refusing to replace a binary or invalid UTF-8 file");
        } catch (IOException error) {
            return ToolResult.failure(callId, definition().name(), "Unable to write " + requested + ": " + error.getMessage());
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The target write is already complete or reported as failed.
                }
            }
        }
    }
}
