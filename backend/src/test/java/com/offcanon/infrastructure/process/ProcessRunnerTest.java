package com.offcanon.infrastructure.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessRunnerTest {
    @TempDir
    Path temp;

    @Test
    void timeoutTerminatesTheProcess() {
        ProcessRunner.ProcessResult result = new ProcessRunner().run(javaCommand("sleep"), temp, Map.of(),
                Duration.ofMillis(250));

        assertTrue(result.timedOut());
        assertFalse(result.cancelled());
        assertTrue(result.duration().compareTo(Duration.ofSeconds(5)) < 0);
    }

    @Test
    void interruptionReturnsCancelledResultAfterTerminatingTheProcess() throws Exception {
        AtomicReference<ProcessRunner.ProcessResult> captured = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> captured.set(new ProcessRunner().run(
                javaCommand("sleep"), temp, Map.of(), Duration.ofSeconds(30))));

        Thread.sleep(250);
        worker.interrupt();
        worker.join(5_000);

        assertFalse(worker.isAlive(), "cancelled process worker did not stop");
        assertNotNull(captured.get());
        assertTrue(captured.get().cancelled());
        assertFalse(captured.get().timedOut());
    }

    @Test
    void oversizedOutputRetainsRealHeadAndTail() {
        ProcessRunner.ProcessResult result = new ProcessRunner().run(javaCommand("output"), temp, Map.of(),
                Duration.ofSeconds(10));

        assertTrue(result.stdout().startsWith("HEAD_MARKER"));
        assertTrue(result.stdout().contains("process output truncated; head/tail retained"));
        assertTrue(result.stdout().endsWith("TAIL_MARKER"));
        assertTrue(result.stdout().length() < 1_100_000);
    }

    @Test
    void inheritsOnlyBuildRuntimeVariablesAndRejectsUnknownExplicitVariables() {
        ProcessRunner runner = new ProcessRunner();

        Map<String, String> sanitized = runner.sanitizedEnvironment(Map.of(
                "PATH", "safe-path",
                "JAVA_HOME", "safe-java",
                "DATABASE_URL", "jdbc:mysql://user:secret@localhost/db",
                "OFFCANON_MODEL_API_KEY", "secret"), Map.of("OFFCANON_EXPERIMENT_ID", "experiment-1"));

        assertEquals("safe-path", sanitized.get("PATH"));
        assertEquals("safe-java", sanitized.get("JAVA_HOME"));
        assertEquals("experiment-1", sanitized.get("OFFCANON_EXPERIMENT_ID"));
        assertFalse(sanitized.containsKey("DATABASE_URL"));
        assertFalse(sanitized.containsKey("OFFCANON_MODEL_API_KEY"));
        assertThrows(IllegalArgumentException.class, () -> runner.sanitizedEnvironment(
                Map.of(), Map.of("DATABASE_URL", "secret")));
    }

    private List<String> javaCommand(String mode) {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        return List.of(executable, "-cp", System.getProperty("java.class.path"),
                OutputProgram.class.getName(), mode);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    public static final class OutputProgram {
        public static void main(String[] args) throws Exception {
            if (args.length > 0 && args[0].equals("sleep")) {
                Thread.sleep(30_000);
                return;
            }
            System.out.print("HEAD_MARKER");
            System.out.print("A".repeat(600_000));
            System.out.print("B".repeat(600_000));
            System.out.print("TAIL_MARKER");
        }
    }
}
