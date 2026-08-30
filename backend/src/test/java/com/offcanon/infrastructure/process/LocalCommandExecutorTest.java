package com.offcanon.infrastructure.process;

import com.offcanon.port.CommandExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class LocalCommandExecutorTest {
    @TempDir
    Path temp;

    @Test
    void gitDiscoveryCannotEscapeTheCommandWorkspace() throws Exception {
        initialiseRepository(temp);
        Path workspace = Files.createDirectories(temp.resolve("runtime/experiments/experiment-1"));

        CommandExecutor.CommandExecution result = execute(workspace, "git rev-parse --show-toplevel");

        assertNotEquals(0, result.exitCode());
    }

    @Test
    void gitDiscoveryStillFindsARepositoryInsideTheCommandWorkspace() throws Exception {
        initialiseRepository(temp);
        Path workspace = Files.createDirectories(temp.resolve("runtime/experiments/experiment-1"));
        initialiseRepository(workspace);

        CommandExecutor.CommandExecution result = execute(workspace, "git rev-parse --show-toplevel");

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals(workspace.toRealPath(), Path.of(result.stdout().trim()).toRealPath());
    }

    private CommandExecutor.CommandExecution execute(Path workspace, String command) {
        return new LocalCommandExecutor(new ProcessRunner()).execute(
                command, workspace, Duration.ofSeconds(20), Map.of(), "test");
    }

    private void initialiseRepository(Path directory) throws Exception {
        Files.createDirectories(directory);
        ProcessRunner.ProcessResult result = new ProcessRunner().run(
                List.of("git", "init", "-q"), directory, Map.of(), Duration.ofSeconds(20));
        assertEquals(0, result.exitCode(), result.stderr());
    }
}
