package com.pico.infrastructure.agent;

import com.pico.agent.domain.ToolDefinition;
import com.pico.agent.domain.ToolResult;
import com.pico.experiment.domain.Experiment;
import com.pico.infrastructure.process.ProcessRunner;
import com.pico.port.Tool;
import com.pico.shared.domain.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ShellTool implements Tool {
    private static final int MAX_OUTPUT_CHARS = 12_000;
    private final ProcessRunner processRunner;
    private final Duration timeout;

    public ShellTool(ProcessRunner processRunner,
                     @Value("${pico.agent.command-timeout-seconds:30}") long timeoutSeconds) {
        this.processRunner = processRunner;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("shell", "Run a non-interactive command in the experiment workspace.", Map.of(
                "type", "object",
                "properties", Map.of("command", Map.of("type", "string")),
                "required", List.of("command")));
    }

    @Override
    public ToolResult execute(Experiment experiment, String callId, Map<String, Object> arguments) {
        String command = ToolArguments.requiredString(arguments, "command");
        try {
            validateCommand(command, experiment.workspacePath());
            List<String> processCommand = isWindows()
                    ? List.of("cmd.exe", "/d", "/s", "/c", command)
                    : List.of("sh", "-lc", command);
            ProcessRunner.ProcessResult result = processRunner.run(processCommand, experiment.workspacePath(),
                    Map.of("PICO_EXPERIMENT_ID", experiment.id().toString()), timeout);
            String output = "exit=" + result.exitCode() + (result.timedOut() ? " timeout=true" : " timeout=false")
                    + "\nstdout:\n" + truncate(result.stdout()) + "\nstderr:\n" + truncate(result.stderr());
            if (result.timedOut()) {
                return ToolResult.failure(callId, definition().name(), "Command timed out after " + timeout.toSeconds() + "s\n" + output);
            }
            if (result.exitCode() != 0) {
                return ToolResult.failure(callId, definition().name(), output);
            }
            return ToolResult.success(callId, definition().name(), output);
        } catch (DomainException error) {
            return ToolResult.failure(callId, definition().name(), error.getMessage());
        }
    }

    private void validateCommand(String command, Path workspace) {
        String normalized = command.toLowerCase(Locale.ROOT);
        List<String> blocked = List.of(
                "git reset --hard", "git clean -f", "git restore", "git update-ref", "git worktree",
                "rm -rf", "rmdir /s", "del /s", "format ", "shutdown ", "stop-computer");
        if (blocked.stream().anyMatch(normalized::contains)) {
            throw new DomainException("DANGEROUS_COMMAND_BLOCKED", "Command is blocked by the experiment policy");
        }
        if (workspace == null) {
            throw new DomainException("WORKSPACE_NOT_READY", "Experiment workspace is not ready");
        }
    }

    private String truncate(String value) {
        if (value.length() <= MAX_OUTPUT_CHARS) return value;
        int head = MAX_OUTPUT_CHARS / 2;
        int tail = MAX_OUTPUT_CHARS - head;
        return value.substring(0, head) + "\n...[truncated]...\n" + value.substring(value.length() - tail);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
