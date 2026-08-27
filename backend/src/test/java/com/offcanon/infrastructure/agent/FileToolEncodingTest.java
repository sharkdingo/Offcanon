package com.offcanon.infrastructure.agent;

import com.offcanon.agent.domain.ToolResult;
import com.offcanon.experiment.domain.Experiment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FileToolEncodingTest {
    @TempDir
    Path workspace;

    @Test
    void refusesToReadOrOverwriteInvalidUtf8WithoutCorruptingIt() throws Exception {
        byte[] original = {(byte) 0xC3, (byte) 0x28};
        Path binary = workspace.resolve("binary.dat");
        Files.write(binary, original);
        Experiment experiment = experiment();
        WorkspacePathResolver paths = new WorkspacePathResolver();

        ToolResult read = new ReadFileTool(paths).execute(experiment, "read", Map.of("path", "binary.dat"));
        ToolResult write = new WriteFileTool(paths).execute(experiment, "write", Map.of("path", "binary.dat", "content", "replacement"));

        assertFalse(read.success());
        assertFalse(write.success());
        assertArrayEquals(original, Files.readAllBytes(binary));
    }

    private Experiment experiment() {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), workspace);
        return experiment;
    }
}
