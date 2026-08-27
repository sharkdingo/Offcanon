package com.offcanon.infrastructure.agent;

import com.offcanon.agent.domain.ToolDefinition;
import com.offcanon.agent.domain.ToolResult;
import com.offcanon.experiment.domain.Experiment;
import com.offcanon.infrastructure.process.ProcessRunner;
import com.offcanon.infrastructure.process.LocalCommandExecutor;
import com.offcanon.port.CommandExecutor;
import com.offcanon.port.EvidenceRepository;
import com.offcanon.port.Tool;
import com.offcanon.port.ProjectRepository;
import com.offcanon.port.SnapshotPort;
import com.offcanon.port.SnapshotRepository;
import com.offcanon.project.domain.Project;
import com.offcanon.shared.domain.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class ShellTool implements Tool {
    static final String INDETERMINATE_EXECUTION = "TOOL_EXECUTION_INDETERMINATE";
    private static final int MAX_OUTPUT_CHARS = 12_000;
    private static final Pattern PARENT_SEGMENT = Pattern.compile("(^|[\\\\/\\s\"'=])\\.\\.([\\\\/\\s\"'=]|$)");
    private static final Pattern WINDOWS_DRIVE_PATH = Pattern.compile(
            "(?i)(?:[a-z]:[\\\\/]|(?:^|[\\s\"'=])[a-z]:)");
    private static final Pattern UNC_ABSOLUTE = Pattern.compile("^\\\\\\\\");
    private static final Pattern POSIX_ABSOLUTE = Pattern.compile("(^|[\\s\"'=])/");
    private static final Pattern SHELL_METACHARACTER = Pattern.compile(
            "[;&|<>`^%$]|(^|[\\s\"'])~(?=[\\\\/]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DANGEROUS_GIT = Pattern.compile(
            "(?i)(^|[\\s\\\"'])git(?:\\.exe)?[\\s\\\"']+(reset|clean|restore|checkout|switch|branch|update-ref|config|worktree|gc|submodule|commit|push|fetch|merge|rebase)(?:[\\s\\\"']|$)");
    private static final Pattern NESTED_INTERPRETER = Pattern.compile(
            "(?i)(^|[\\s\\\"'])(?:powershell|pwsh|bash|zsh|sh|cmd(?:\\.exe)?|wsl|python(?:3)?|node|perl|ruby)(?:[\\s\\\"']|$)");
    private static final Pattern DESTRUCTIVE_COMMAND = Pattern.compile(
            "(?i)(^|[\\s\\\"'])(?:rm|rmdir|del|erase|format|shutdown|stop-computer|remove-item)(?:[\\s\\\"']|$)");
    private static final Pattern NETWORK_COMMAND = Pattern.compile(
            "(?i)(^|[\\s\\\"'])(?:curl|wget|certutil|bitsadmin|invoke-webrequest)(?:[\\s\\\"']|$)");
    private final CommandExecutor commandExecutor;
    private final ProjectRepository projects;
    private final EvidenceRepository evidence;
    private final SnapshotPort snapshots;
    private final SnapshotRepository snapshotRepository;
    private final Duration timeout;

    @org.springframework.beans.factory.annotation.Autowired
    public ShellTool(CommandExecutor commandExecutor,
                     ProjectRepository projects,
                     EvidenceRepository evidence,
                     SnapshotPort snapshots,
                     SnapshotRepository snapshotRepository,
                     @Value("${offcanon.agent.command-timeout-seconds:30}") long timeoutSeconds) {
        this.commandExecutor = commandExecutor;
        this.projects = projects;
        this.evidence = evidence;
        this.snapshots = snapshots;
        this.snapshotRepository = snapshotRepository;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    public ShellTool(ProcessRunner processRunner, long timeoutSeconds) {
        this(new LocalCommandExecutor(processRunner), null, null, null, null, timeoutSeconds);
    }

    public ShellTool(ProcessRunner processRunner, ProjectRepository projects, long timeoutSeconds) {
        this(new LocalCommandExecutor(processRunner), projects, null, null, null, timeoutSeconds);
    }

    public ShellTool(ProcessRunner processRunner,
                     ProjectRepository projects,
                     EvidenceRepository evidence,
                     SnapshotPort snapshots,
                     SnapshotRepository snapshotRepository,
                     long timeoutSeconds) {
        this(new LocalCommandExecutor(processRunner), projects, evidence, snapshots, snapshotRepository, timeoutSeconds);
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
            Project project = projects == null ? null : projects.findById(experiment.projectId())
                    .orElseThrow(() -> new DomainException("PROJECT_NOT_FOUND", "Project is no longer available"));
            Path canonical = project == null ? null : project.canonicalPath().toAbsolutePath().normalize();
            validateCommand(command, experiment.workspacePath(), canonical);
            Instant started = Instant.now();
            CommandExecutor.CommandExecution result = commandExecutor.execute(command, experiment.workspacePath(),
                    timeout, Map.of("OFFCANON_EXPERIMENT_ID", experiment.id().toString()), "agent-shell");
            Instant completed = Instant.now();
            boolean interrupted = Thread.interrupted();
            try {
                try {
                    recordEvidence(project, experiment, command, result, started, completed);
                } catch (RuntimeException evidenceFailure) {
                    throw new DomainException(INDETERMINATE_EXECUTION,
                            "Command finished, but its resulting state could not be sealed as evidence; "
                                    + "the run stopped to avoid repeating a possible side effect");
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
            String output = "exit=" + result.exitCode() + (result.timedOut() ? " timeout=true" : " timeout=false")
                    + (result.cancelled() ? " cancelled=true" : " cancelled=false")
                    + "\nstdout:\n" + truncate(result.stdout()) + "\nstderr:\n" + truncate(result.stderr());
            if (result.cancelled()) {
                return ToolResult.failure(callId, definition().name(), "Command cancelled\n" + output);
            }
            if (result.timedOut()) {
                return ToolResult.failure(callId, definition().name(), "Command timed out after " + timeout.toSeconds() + "s\n" + output);
            }
            if (result.exitCode() != 0) {
                return ToolResult.failure(callId, definition().name(), output);
            }
            return ToolResult.success(callId, definition().name(), output);
        } catch (DomainException error) {
            if (INDETERMINATE_EXECUTION.equals(error.code())) throw error;
            return ToolResult.failure(callId, definition().name(), error.getMessage());
        }
    }

    private void recordEvidence(Project project,
                                Experiment experiment,
                                String command,
                                CommandExecutor.CommandExecution execution,
                                java.time.Instant started,
                                java.time.Instant completed) {
        if (evidence == null || snapshots == null || snapshotRepository == null
                || project == null || experiment.baseSnapshotId() == null) return;
        var base = snapshotRepository.findById(experiment.baseSnapshotId())
                .orElseThrow(() -> new DomainException("BASE_SNAPSHOT_MISSING", "Base snapshot is unavailable for command evidence"));
        var observed = snapshots.captureWorkspace(project, experiment.workspacePath(), base.fingerprint());
        snapshotRepository.save(observed);
        evidence.save(com.offcanon.verification.domain.Evidence.command(experiment.id(), observed.id(),
                command, experiment.workspacePath().toString(), execution.exitCode(),
                truncate(execution.stdout()), truncate(execution.stderr()), started, completed,
                execution.duration(), execution.timedOut(), execution.cancelled(), execution.environmentProfile()));
    }

    private void validateCommand(String command, Path workspace, Path canonical) {
        String dequoted = command.toLowerCase(Locale.ROOT).replace("\"", "").replace("'", "");
        String commandView = dequoted.replace("\\", "");
        String pathView = dequoted.replace('\\', '/');
        if (DANGEROUS_GIT.matcher(commandView).find()
                || NESTED_INTERPRETER.matcher(commandView).find()
                || DESTRUCTIVE_COMMAND.matcher(commandView).find()
                || NETWORK_COMMAND.matcher(commandView).find()
                || commandView.contains("start-process")
                || commandView.contains("invoke-expression")
                || commandView.matches(".*(^|[\\s\\\"'])iex(?:[\\s\\\"']|$).*") ) {
            throw new DomainException("DANGEROUS_COMMAND_BLOCKED", "Command is blocked by the experiment policy");
        }
        if (SHELL_METACHARACTER.matcher(command).find()) {
            throw new DomainException("DANGEROUS_COMMAND_BLOCKED", "Shell operators, command substitution and environment expansion are blocked by the experiment policy");
        }
        if (PARENT_SEGMENT.matcher(pathView).find()
                || WINDOWS_DRIVE_PATH.matcher(pathView).find()
                || UNC_ABSOLUTE.matcher(pathView).find()
                || POSIX_ABSOLUTE.matcher(pathView).find()) {
            throw new DomainException("WORKSPACE_PATH_BLOCKED", "Shell command contains an absolute or parent-traversal path");
        }
        if (canonical != null) {
            String canonicalText = canonical.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (pathView.contains(canonicalText)) {
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

}
