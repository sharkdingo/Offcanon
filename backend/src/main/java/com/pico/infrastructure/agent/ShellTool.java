package com.pico.infrastructure.agent;

import com.pico.agent.domain.ToolDefinition;
import com.pico.agent.domain.ToolResult;
import com.pico.experiment.domain.Experiment;
import com.pico.infrastructure.process.ProcessRunner;
import com.pico.port.Tool;
import com.pico.port.ProjectRepository;
import com.pico.shared.domain.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class ShellTool implements Tool {
    private static final int MAX_OUTPUT_CHARS = 12_000;
    private static final Pattern PARENT_SEGMENT = Pattern.compile("(^|[\\\\/\\s])\\.\\.([\\\\/\\s]|$)");
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("[a-z]:[\\\\/]");
    private static final Pattern UNC_ABSOLUTE = Pattern.compile("^\\\\\\\\");
    private static final Pattern POSIX_ABSOLUTE = Pattern.compile("(^|[\\s\"'])/");
    private final ProcessRunner processRunner;
    private final ProjectRepository projects;
    private final Duration timeout;

    @org.springframework.beans.factory.annotation.Autowired
    public ShellTool(ProcessRunner processRunner,
                     ProjectRepository projects,
                     @Value("${pico.agent.command-timeout-seconds:30}") long timeoutSeconds) {
        this.processRunner = processRunner;
        this.projects = projects;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    public ShellTool(ProcessRunner processRunner, long timeoutSeconds) {
        this(processRunner, null, timeoutSeconds);
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
            Path canonical = projects == null ? null : projects.findById(experiment.projectId())
                    .map(project -> project.canonicalPath().toAbsolutePath().normalize())
                    .orElseThrow(() -> new DomainException("PROJECT_NOT_FOUND", "Project is no longer available"));
            validateCommand(command, experiment.workspacePath(), canonical);
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

    private void validateCommand(String command, Path workspace, Path canonical) {
        String normalized = command.replace('\\', '/').toLowerCase(Locale.ROOT);
        List<String> blocked = List.of(
                "git reset --hard", "git clean -f", "git restore", "git update-ref", "git worktree",
                "rm -rf", "rmdir /s", "del /s", "format ", "shutdown ", "stop-computer",
                "powershell", "pwsh", "bash -c", "cmd /c", "cmd.exe /c", "start-process",
                "invoke-expression", "iex ", "curl ", "wget ", "certutil", "bitsadmin",
                "python -c", "python3 -c", "node -e");
        if (blocked.stream().anyMatch(normalized::contains)) {
            throw new DomainException("DANGEROUS_COMMAND_BLOCKED", "Command is blocked by the experiment policy");
        }
        if (PARENT_SEGMENT.matcher(normalized).find()
                || WINDOWS_ABSOLUTE.matcher(normalized).find()
                || UNC_ABSOLUTE.matcher(normalized).find()
                || POSIX_ABSOLUTE.matcher(normalized).find()) {
            throw new DomainException("WORKSPACE_PATH_BLOCKED", "Shell command contains an absolute or parent-traversal path");
        }
        if (canonical != null) {
            String canonicalText = canonical.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (normalized.contains(canonicalText)) {
                throw new DomainException("CANONICAL_ACCESS_BLOCKED", "Shell command references the canonical workspace");
            }
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
