package com.offcanon.infrastructure.agent;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.agent.domain.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeleteFileToolTest {
    @TempDir
    Path temp;

    @Test
    void deletesOnlyRegularFilesInsideTheExperimentWorkspace() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        Path file = workspace.resolve("obsolete.txt");
        Files.writeString(file, "remove me\n");
        Files.createDirectories(workspace.resolve("directory"));
        Files.writeString(workspace.resolve(".env"), "secret=value\n");
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), workspace);
        DeleteFileTool tool = new DeleteFileTool(new WorkspacePathResolver());
        ToolRegistryImpl registry = new ToolRegistryImpl(java.util.List.of(tool));

        assertTrue(registry.dispatch(experiment,
                new ToolCall("delete", "delete_file", Map.of("path", "obsolete.txt"))).success());
        assertFalse(Files.exists(file));
        assertFalse(registry.dispatch(experiment,
                new ToolCall("missing", "delete_file", Map.of("path", "missing.txt"))).success());
        assertFalse(registry.dispatch(experiment,
                new ToolCall("directory", "delete_file", Map.of("path", "directory"))).success());
        assertFalse(registry.dispatch(experiment,
                new ToolCall("outside", "delete_file", Map.of("path", "../outside.txt"))).success());
        assertFalse(registry.dispatch(experiment,
                new ToolCall("protected", "delete_file", Map.of("path", ".env"))).success());
        assertTrue(Files.exists(workspace.resolve("directory")));
        assertTrue(Files.exists(workspace.resolve(".env")));
    }
}
