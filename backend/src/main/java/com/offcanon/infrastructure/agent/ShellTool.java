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
import com.offcanon.shared.domain.SensitivePathPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
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
    private static final Pattern SHELL_EXPANSION = Pattern.compile(
            "[;<>`^%$]|(^|[\\s\"'])~(?=[\\\\/]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DANGEROUS_GIT = Pattern.compile(
            "(?i)(^|[\\s\\\"'&|])git(?:\\.exe)?[\\s\\\"']+(reset|clean|restore|checkout|switch|branch|update-ref|config|worktree|gc|submodule|commit|push|fetch|pull|clone|ls-remote|merge|rebase)(?:[\\s\\\"'&|]|$)");
    private static final Pattern NESTED_SHELL = Pattern.compile(
            "(?i)(^|[\\s\\\"'&|])(?:powershell|pwsh|bash|zsh|sh|cmd(?:\\.exe)?|wsl)(?:[\\s\\\"'&|]|$)");
    private static final Pattern INLINE_RUNTIME_CODE = Pattern.compile(
            "(?i)(^|[\\s\\\"'&|])(?:python(?:3)?(?:\\.exe)?[\\s\\\"']+-c|"
                    + "node(?:\\.exe)?[\\s\\\"']+(?:-e|--eval|-p|--print)|"
                    + "(?:perl|ruby)(?:\\.exe)?[\\s\\\"']+-e)(?:[=\\s\\\"'&|]|$)");
    private static final Pattern DESTRUCTIVE_COMMAND = Pattern.compile(
            "(?i)(^|[\\s\\\"'&|])(?:rm|rmdir|del|erase|format|shutdown|stop-computer|remove-item)(?:[\\s\\\"'&|]|$)");
    private static final Pattern NETWORK_COMMAND = Pattern.compile(
            "(?i)(^|[\\s\\\"'&|])(?:curl|wget|certutil|bitsadmin|invoke-webrequest|ssh|scp|sftp|ftp|telnet|nc|ncat)(?:[\\s\\\"'&|]|$)");
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
        String shell = isWindows() ? "Windows cmd.exe" : "POSIX sh";
        return new ToolDefinition("shell", "Run a non-interactive command in the experiment workspace using " + shell + ". "
                + "Normal && and || chaining is supported, as are bounded non-sensitive environment variables. "
                + "Use read_file, write_file, delete_file, list_files and search_files for workspace edits and inspection. "
                + "Pipes, background execution, nested shells, inline code, redirection, and canonical paths are blocked by application-level guardrails; this is not an OS sandbox.", Map.of(
                "type", "object",
                "properties", Map.of(
                        "command", Map.of("type", "string"),
                        "environment", Map.of(
                                "type", "object",
                                "additionalProperties", Map.of("type", "string"),
                                "maxProperties", ProcessRunner.MAX_REQUESTED_ENVIRONMENT_ENTRIES)),
                "required", List.of("command")));
    }

    @Override
    public ToolResult execute(Experiment experiment, String callId, Map<String, Object> arguments) {
        String command = ToolArguments.requiredString(arguments, "command");
        try {
            Map<String, String> requestedEnvironment = requestedEnvironment(arguments);
            Project project = projects == null ? null : projects.findById(experiment.projectId())
                    .orElseThrow(() -> new DomainException("PROJECT_NOT_FOUND", "Project is no longer available"));
            Path canonical = project == null ? null : project.canonicalPath().toAbsolutePath().normalize();
            validateCommand(command, experiment.workspacePath(), canonical);
            Map<String, String> executionEnvironment = new LinkedHashMap<>(requestedEnvironment);
            executionEnvironment.put("OFFCANON_EXPERIMENT_ID", experiment.id().toString());
            Instant started = Instant.now();
            CommandExecutor.CommandExecution result = commandExecutor.execute(command, experiment.workspacePath(),
                    timeout, executionEnvironment, "agent-shell");
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
                || NESTED_SHELL.matcher(commandView).find()
                || INLINE_RUNTIME_CODE.matcher(commandView).find()
                || DESTRUCTIVE_COMMAND.matcher(commandView).find()
                || NETWORK_COMMAND.matcher(commandView).find()
                || SensitivePathPolicy.containsSensitivePathReference(pathView)
                || commandView.contains("start-process")
                || commandView.contains("invoke-expression")
                || commandView.matches(".*(^|[\\s\\\"'])iex(?:[\\s\\\"']|$).*") ) {
            throw new DomainException("DANGEROUS_COMMAND_BLOCKED", "Command is blocked by the experiment policy");
        }
        validateShellOperators(command);
        if (SHELL_EXPANSION.matcher(command).find()) {
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

    private Map<String, String> requestedEnvironment(Map<String, Object> arguments) {
        Object raw = arguments.get("environment");
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> values)) {
            throw new DomainException("INVALID_TOOL_ARGUMENTS", "Tool argument 'environment' must be an object of string values");
        }
        if (values.size() > ProcessRunner.MAX_REQUESTED_ENVIRONMENT_ENTRIES) {
            throw new DomainException("INVALID_TOOL_ARGUMENTS", "Tool argument 'environment' has too many entries (maximum "
                    + ProcessRunner.MAX_REQUESTED_ENVIRONMENT_ENTRIES + ")");
        }
        Map<String, String> requested = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof String value)) {
                throw new DomainException("INVALID_TOOL_ARGUMENTS", "Tool argument 'environment' must contain only string names and values");
            }
            try {
                ProcessRunner.validateRequestedEnvironmentEntry(name, value);
            } catch (IllegalArgumentException error) {
                throw new DomainException("INVALID_TOOL_ARGUMENTS", error.getMessage());
            }
            String normalized = name.toUpperCase(Locale.ROOT);
            if (requested.keySet().stream().anyMatch(existing -> existing.toUpperCase(Locale.ROOT).equals(normalized))) {
                throw new DomainException("INVALID_TOOL_ARGUMENTS", "Tool argument 'environment' contains duplicate variable names");
            }
            if (normalized.startsWith("GIT_") || normalized.startsWith("OFFCANON_")) {
                throw new DomainException("INVALID_TOOL_ARGUMENTS", "Tool argument 'environment' cannot override Offcanon or Git control variables");
            }
            requested.put(name, value);
        }
        return Map.copyOf(requested);
    }

    private void validateShellOperators(String command) {
        for (int index = 0; index < command.length(); index++) {
            char current = command.charAt(index);
            if (current == '\r' || current == '\n' || current == '(' || current == ')') {
                throw new DomainException("DANGEROUS_COMMAND_BLOCKED", "Shell operators, command substitution and environment expansion are blocked by the experiment policy");
            }
            if (current != '&' && current != '|') continue;
            if (index + 1 < command.length() && command.charAt(index + 1) == current) {
                index++;
                continue;
            }
            throw new DomainException("DANGEROUS_COMMAND_BLOCKED", "Shell operators, command substitution and environment expansion are blocked by the experiment policy");
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private String truncate(String value) {
        if (value.length() <= MAX_OUTPUT_CHARS) return value;
        int head = MAX_OUTPUT_CHARS / 2;
        int tail = MAX_OUTPUT_CHARS - head;
        return value.substring(0, head) + "\n...[truncated]...\n" + value.substring(value.length() - tail);
    }

}
