package com.offcanon.infrastructure.agent;

import com.offcanon.agent.domain.ToolResult;
import com.offcanon.experiment.domain.Experiment;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void allowsClearingAnExistingTextFile() throws Exception {
        Path file = workspace.resolve("clear.txt");
        Files.writeString(file, "content\n");
        ToolResult result = new WriteFileTool(new WorkspacePathResolver()).execute(
                experiment(), "clear", Map.of("path", "clear.txt", "content", ""));

        assertTrue(result.success(), result.output());
        assertTrue(Files.exists(file));
        assertTrue(Files.readString(file).isEmpty());
    }

    @Test
    void preservesExecutableModeWhenReplacingText() throws Exception {
        Path script = workspace.resolve("run.sh");
        Files.writeString(script, "echo old\n");
        try {
            Files.setPosixFilePermissions(script, java.util.Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException error) {
            Assumptions.assumeTrue(false, "POSIX permissions are unavailable on this workstation");
        }

        ToolResult result = new WriteFileTool(new WorkspacePathResolver()).execute(
                experiment(), "write-mode", Map.of("path", "run.sh", "content", "echo new\n"));

        assertTrue(result.success(), result.output());
        assertTrue(Files.isExecutable(script));
        assertTrue(Files.readString(script).contains("echo new"));
    }

    private Experiment experiment() {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), workspace);
        return experiment;
    }
}
