package com.offcanon.infrastructure.agent;

import com.offcanon.agent.domain.ToolDefinition;
import com.offcanon.agent.domain.ToolResult;
import com.offcanon.experiment.domain.Experiment;
import com.offcanon.infrastructure.filesystem.GitFileMode;
import com.offcanon.port.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
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
        // An empty string is a valid replacement: clearing a text file is a
        // normal edit, while the path itself remains required and non-blank.
        String content = ToolArguments.requiredStringValue(arguments, "content");
        if (content.length() > MAX_CHARS) {
            return ToolResult.failure(callId, definition().name(), "File content exceeds " + MAX_CHARS + " characters");
        }
        Path path = paths.resolve(experiment, requested, true);
        Path temporary = null;
        int replacementMode = GitFileMode.REGULAR;
        try {
            if (Files.exists(path)) {
                // Bound the validation read as well as the initial size
                // check. A concurrent replacement can otherwise make
                // readAllBytes allocate an unbounded buffer.
                byte[] existing;
                try (InputStream input = Files.newInputStream(path)) {
                    existing = input.readNBytes(Math.toIntExact(MAX_EXISTING_BYTES) + 1);
                }
                if (existing.length > MAX_EXISTING_BYTES) {
                    return ToolResult.failure(callId, definition().name(), "Existing file is too large to replace as text");
                }
                Utf8Text.decode(existing);
                replacementMode = GitFileMode.read(path);
            }
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), ".offcanon-write-", ".tmp");
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            GitFileMode.apply(temporary, replacementMode);
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
