package com.offcanon.infrastructure.agent;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.infrastructure.process.ProcessRunner;
import com.offcanon.agent.domain.ToolResult;
import com.offcanon.infrastructure.git.GitSnapshotAdapter;
import com.offcanon.infrastructure.memory.InMemoryEvidenceRepository;
import com.offcanon.infrastructure.memory.InMemoryProjectRepository;
import com.offcanon.infrastructure.memory.InMemorySnapshotRepository;
import com.offcanon.infrastructure.workspace.LocalWorkspaceAdapter;
import com.offcanon.project.domain.Project;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.workspace.domain.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        ToolResult driveRelative = tool.execute(experiment, "3", Map.of("command", "type C:Windows\\win.ini"));
        ToolResult escapedDrive = tool.execute(experiment, "4", Map.of("command", "type C^:\\secrets\\key.txt"));
        ToolResult quotedDrive = tool.execute(experiment, "5", Map.of("command", "type C\":Windows\\win.ini\""));
        ToolResult doubledQuotes = tool.execute(experiment, "6", Map.of("command", "type C\"\":Windows\\win.ini\""));
        ToolResult wrappedQuotes = tool.execute(experiment, "7", Map.of("command", "type \"C\":Windows\\win.ini\""));
        ToolResult optionAttachedDrive = tool.execute(experiment, "8", Map.of(
                "command", "git --git-dir=C:\\outside\\.git show HEAD:service.txt"));
        ToolResult optionAttachedParent = tool.execute(experiment, "9", Map.of(
                "command", "git --git-dir=../outside/.git show HEAD:service.txt"));
        ToolResult optionAttachedPosix = tool.execute(experiment, "10", Map.of(
                "command", "git --git-dir=/tmp/outside.git show HEAD:service.txt"));
        ToolResult optionAttachedUnc = tool.execute(experiment, "11", Map.of(
                "command", "git --git-dir=\\\\server\\share\\outside.git show HEAD:service.txt"));

        assertFalse(parent.success());
        assertFalse(absolute.success());
        assertFalse(driveRelative.success());
        assertFalse(escapedDrive.success());
        assertFalse(quotedDrive.success());
        assertFalse(doubledQuotes.success());
        assertFalse(wrappedQuotes.success());
        assertFalse(optionAttachedDrive.success());
        assertFalse(optionAttachedParent.success());
        assertFalse(optionAttachedPosix.success());
        assertFalse(optionAttachedUnc.success());
        assertTrue(optionAttachedDrive.error().contains("absolute or parent-traversal"));
        assertTrue(optionAttachedParent.error().contains("absolute or parent-traversal"));
        assertTrue(optionAttachedPosix.error().contains("absolute or parent-traversal"));
        assertTrue(optionAttachedUnc.error().contains("absolute or parent-traversal"));
        assertTrue(parent.error().contains("workspace") || parent.error().contains("path"));
    }

    @Test
    void blocksExpansionNestedShellsInlineCodeAndRedirection() {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), temp);

        ShellTool tool = new ShellTool(new ProcessRunner(), 5);
        assertFalse(tool.execute(experiment, "1", Map.of("command", "type %USERPROFILE%\\secrets.txt")).success());
        assertFalse(tool.execute(experiment, "2", Map.of("command", "type note.txt > outside.txt")).success());
        assertFalse(tool.execute(experiment, "3", Map.of("command", "python -c \"print('escape')\"")).success());
        assertFalse(tool.execute(experiment, "4", Map.of("command", "git checkout -- service.txt")).success());
        assertFalse(tool.execute(experiment, "5", Map.of("command", "git config user.name offcanon")).success());
        assertFalse(tool.execute(experiment, "6", Map.of("command", "type \"..\\outside.txt\"")).success());
        assertFalse(tool.execute(experiment, "7", Map.of("command", "cat '../outside.txt'")).success());
        assertFalse(tool.execute(experiment, "8", Map.of("command", "cat $HOME/.ssh/id_rsa")).success());
        assertFalse(tool.execute(experiment, "9", Map.of("command", "cat ${HOME}/.ssh/id_rsa")).success());
        assertFalse(tool.execute(experiment, "10", Map.of("command", "cat ~/.ssh/id_rsa")).success());
        assertFalse(tool.execute(experiment, "11", Map.of("command", "node --eval \"process.exit(0)\"")).success());
        assertFalse(tool.execute(experiment, "12", Map.of("command", "cmd /c type note.txt")).success());
        assertFalse(tool.execute(experiment, "13", Map.of("command", "bash -lc 'cat note.txt'")).success());
    }

    @Test
    void blocksDirectReadsOfSensitiveEnvironmentFiles() throws Exception {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), temp);
        Files.writeString(temp.resolve(".env.example"), "TOKEN=replace-me\n");

        ShellTool tool = new ShellTool(new ProcessRunner(), 5);
        ToolResult env = tool.execute(experiment, "env", Map.of("command", "type .env"));
        ToolResult quotedEnv = tool.execute(experiment, "env-quoted", Map.of("command", "type \".env\""));
        ToolResult envLocal = tool.execute(experiment, "env-local", Map.of("command", "type config/.env.local"));
        ToolResult template = tool.execute(experiment, "env-template", Map.of("command", "type .env.example"));

        assertFalse(env.success());
        assertFalse(quotedEnv.success());
        assertFalse(envLocal.success());
        assertTrue(template.success(), template.error());
        assertTrue(env.error().contains("blocked"));
        assertTrue(envLocal.error().contains("blocked"));
    }

    @Test
    void blocksObviousRemoteGitAndNetworkCommands() {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), temp);

        ShellTool tool = new ShellTool(new ProcessRunner(), 5);
        assertFalse(tool.execute(experiment, "clone", Map.of("command", "git clone https://example.invalid/repo.git")).success());
        assertFalse(tool.execute(experiment, "pull", Map.of("command", "git pull origin main")).success());
        assertFalse(tool.execute(experiment, "remote", Map.of("command", "git ls-remote origin")).success());
        assertFalse(tool.execute(experiment, "ssh", Map.of("command", "ssh example.invalid")).success());
    }

    @Test
    void allowsProjectLanguageRuntimesToExecuteWorkspaceFiles() {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), temp);
        AtomicReference<String> captured = new AtomicReference<>();
        com.offcanon.port.CommandExecutor executor = (command, cwd, timeout, environment, profile) -> {
            captured.set(command);
            return new com.offcanon.port.CommandExecutor.CommandExecution(0, "ok", "", Duration.ZERO,
                    false, false, "test");
        };
        ShellTool tool = new ShellTool(executor, null, null, null, null, 5);

        assertTrue(tool.execute(experiment, "node", Map.of(
                "command", "node src/cli.js Ideas")).success());
        assertEquals("node src/cli.js Ideas", captured.get());
        assertTrue(tool.execute(experiment, "python", Map.of(
                "command", "python scripts/check.py")).success());
        assertEquals("python scripts/check.py", captured.get());
    }

    @Test
    void allowsNonPathColonSyntax() {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), temp);
        com.offcanon.port.CommandExecutor executor = (command, cwd, timeout, environment, profile) ->
                new com.offcanon.port.CommandExecutor.CommandExecution(0, command, "", Duration.ZERO,
                        false, false, "test");
        ShellTool tool = new ShellTool(executor, null, null, null, null, 5);

        assertTrue(tool.execute(experiment, "git", Map.of("command", "git show HEAD:service.txt")).success());
        assertTrue(tool.execute(experiment, "maven", Map.of(
                "command", "mvn dependency:get -Dartifact=com.foo:bar:1.0")).success());
    }

    @Test
    void allowsLogicalChainingAndPassesBoundedEnvironment() {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), temp);
        AtomicReference<String> capturedCommand = new AtomicReference<>();
        AtomicReference<Map<String, String>> capturedEnvironment = new AtomicReference<>();
        com.offcanon.port.CommandExecutor executor = (command, cwd, timeout, environment, profile) -> {
            capturedCommand.set(command);
            capturedEnvironment.set(new HashMap<>(environment));
            return new com.offcanon.port.CommandExecutor.CommandExecution(0, "ok", "", Duration.ZERO,
                    false, false, "test");
        };
        ShellTool tool = new ShellTool(executor, null, null, null, null, 5);

        ToolResult result = tool.execute(experiment, "chain", Map.of(
                "command", "node scripts/check.js && python scripts/report.py || echo fallback",
                "environment", Map.of("NODE_ENV", "test", "CI", "1")));

        assertTrue(result.success());
        assertEquals("node scripts/check.js && python scripts/report.py || echo fallback", capturedCommand.get());
        assertEquals("test", capturedEnvironment.get().get("NODE_ENV"));
        assertEquals("1", capturedEnvironment.get().get("CI"));
        assertEquals(experiment.id().toString(), capturedEnvironment.get().get("OFFCANON_EXPERIMENT_ID"));
    }

    @Test
    void rejectsSinglePipeBackgroundAndSemicolonOperators() {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), temp);
        ShellTool tool = new ShellTool(new ProcessRunner(), 5);

        assertFalse(tool.execute(experiment, "pipe", Map.of("command", "type note.txt | findstr note")).success());
        assertFalse(tool.execute(experiment, "background", Map.of("command", "type note.txt & echo done")).success());
        assertFalse(tool.execute(experiment, "semicolon", Map.of("command", "type note.txt; echo done")).success());
    }

    @Test
    void stillBlocksDangerousCommandsAfterLogicalOperators() {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), temp);
        ShellTool tool = new ShellTool(new ProcessRunner(), 5);

        assertFalse(tool.execute(experiment, "git-chain", Map.of("command", "echo ok && git reset --hard HEAD")).success());
        assertFalse(tool.execute(experiment, "delete-chain", Map.of("command", "echo ok || del note.txt")).success());
        assertFalse(tool.execute(experiment, "shell-chain", Map.of("command", "echo ok && cmd /c whoami")).success());
        assertFalse(tool.execute(experiment, "newline", Map.of("command", "echo ok\ndel note.txt")).success());
    }

    @Test
    void rejectsReservedAndSensitiveEnvironmentNames() {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), temp);
        ShellTool tool = new ShellTool(new ProcessRunner(), 5);

        ToolResult path = tool.execute(experiment, "path", Map.of("command", "echo ok",
                "environment", Map.of("PATH", "C:\\outside")));
        ToolResult token = tool.execute(experiment, "token", Map.of("command", "echo ok",
                "environment", Map.of("BUILD_TOKEN", "secret")));
        ToolResult control = tool.execute(experiment, "control", Map.of("command", "echo ok",
                "environment", Map.of("GIT_DIR", "outside")));

        assertFalse(path.success());
        assertFalse(token.success());
        assertFalse(control.success());
        assertTrue(path.error().contains("environment"));
        assertTrue(token.error().contains("environment"));
        assertTrue(control.error().contains("environment"));
    }

    @Test
    void cancelledCommandStillPersistsEvidenceAgainstAnObservedSnapshot() throws Exception {
        Path canonical = temp.resolve("canonical");
        Files.createDirectories(canonical);
        run(canonical, "git", "init", "-q");
        run(canonical, "git", "config", "user.email", "offcanon-test@example.invalid");
        run(canonical, "git", "config", "user.name", "Offcanon Test");
        Files.writeString(canonical.resolve("service.txt"), "base\n");
        run(canonical, "git", "add", "service.txt");
        run(canonical, "git", "commit", "-qm", "initial");
        ProcessRunner runner = new ProcessRunner();
        GitSnapshotAdapter snapshots = new GitSnapshotAdapter(runner, temp.resolve("data").toString());
        InMemorySnapshotRepository snapshotRepository = new InMemorySnapshotRepository();
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        InMemoryEvidenceRepository evidence = new InMemoryEvidenceRepository();
        Project project = projects.save(Project.create(java.util.UUID.randomUUID(), "demo", canonical, List.of("java -version"), Instant.now()));
        var base = snapshotRepository.save(snapshots.capture(project));
        Experiment experiment = Experiment.create(project.id(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(base.id(), new LocalWorkspaceAdapter(temp.resolve("data").toString())
                .materialize(base, experiment.id()));
        ShellTool tool = new ShellTool(runner, projects, evidence, snapshots, snapshotRepository, 30);
        AtomicReference<ToolResult> result = new AtomicReference<>();
        String command = isWindows() ? "ping -n 30 127.0.0.1" : "sleep 30";
        Thread worker = Thread.ofVirtual().start(() -> result.set(tool.execute(experiment, "cancel", Map.of("command", command))));

        Thread.sleep(300);
        worker.interrupt();
        worker.join(10_000);

        assertFalse(worker.isAlive());
        assertFalse(result.get().success());
        var item = evidence.findByExperimentId(experiment.id()).stream().findFirst().orElseThrow();
        assertTrue(item.cancelled());
        assertEquals("agent-shell", item.environmentProfile());
        assertTrue(snapshotRepository.findById(item.snapshotId()).isPresent());
    }

    @Test
    void evidenceFailureAfterCommandExecutionStopsTheRunAsIndeterminate() {
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        InMemoryEvidenceRepository evidence = new InMemoryEvidenceRepository();
        InMemorySnapshotRepository snapshotRepository = new InMemorySnapshotRepository();
        Project project = projects.save(Project.create(java.util.UUID.randomUUID(), "demo", temp, List.of("java -version"), Instant.now()));
        Snapshot base = snapshotRepository.save(new Snapshot(UUID.randomUUID(), project.id(), "base", temp,
                Instant.now(), List.of(), List.of()));
        Experiment experiment = Experiment.create(project.id(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(base.id(), temp);
        com.offcanon.port.SnapshotPort brokenSnapshots = new com.offcanon.port.SnapshotPort() {
            @Override public Snapshot capture(Project ignored) { throw new UnsupportedOperationException(); }
            @Override public Snapshot captureWorkspace(Project ignored, Path workspace, String parent) {
                throw new DomainException("SNAPSHOT_FAILED", "simulated evidence failure");
            }
            @Override public String currentFingerprint(Project ignored) { throw new UnsupportedOperationException(); }
            @Override public String fingerprintWorkspace(Project ignored, Path workspace, String parent) {
                throw new UnsupportedOperationException();
            }
        };
        ToolRegistryImpl registry = new ToolRegistryImpl(List.of(new ShellTool(new ProcessRunner(), projects,
                evidence, brokenSnapshots, snapshotRepository, 5)));

        DomainException error = assertThrows(DomainException.class, () -> registry.dispatch(experiment,
                new com.offcanon.agent.domain.ToolCall("call", "shell", Map.of("command", "java -version"))));

        assertEquals(ShellTool.INDETERMINATE_EXECUTION, error.code());
    }

    private void run(Path cwd, String... command) {
        ProcessRunner.ProcessResult result = new ProcessRunner().run(List.of(command), cwd, Map.of(), java.time.Duration.ofSeconds(20));
        if (result.exitCode() != 0) throw new AssertionError(result.stderr());
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
