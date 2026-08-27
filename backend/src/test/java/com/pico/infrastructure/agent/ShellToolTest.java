package com.pico.infrastructure.agent;

import com.pico.experiment.domain.Experiment;
import com.pico.infrastructure.process.ProcessRunner;
import com.pico.agent.domain.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellToolTest {
    @TempDir
    Path temp;

    @Test
    void blocksPathsThatCouldLeaveExperimentWorkspace() throws Exception {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), temp);

        ShellTool tool = new ShellTool(new ProcessRunner(), 5);
        ToolResult parent = tool.execute(experiment, "1", Map.of("command", "type ..\\outside.txt"));
        ToolResult absolute = tool.execute(experiment, "2", Map.of("command", "type C:\\secrets\\key.txt"));

        assertFalse(parent.success());
        assertFalse(absolute.success());
        assertTrue(parent.error().contains("workspace") || parent.error().contains("path"));
    }
}
