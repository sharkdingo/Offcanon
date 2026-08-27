package com.pico.infrastructure.agent;

import com.pico.experiment.domain.Experiment;
import com.pico.infrastructure.process.ProcessRunner;
import com.pico.agent.domain.ToolResult;
import com.pico.infrastructure.git.GitSnapshotAdapter;
import com.pico.infrastructure.memory.InMemoryEvidenceRepository;
import com.pico.infrastructure.memory.InMemoryProjectRepository;
import com.pico.infrastructure.memory.InMemorySnapshotRepository;
import com.pico.infrastructure.workspace.LocalWorkspaceAdapter;
import com.pico.project.domain.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void blocksExpansionInterpretersAndRedirection() {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), temp);

        ShellTool tool = new ShellTool(new ProcessRunner(), 5);
        assertFalse(tool.execute(experiment, "1", Map.of("command", "type %USERPROFILE%\\secrets.txt")).success());
        assertFalse(tool.execute(experiment, "2", Map.of("command", "type note.txt > outside.txt")).success());
        assertFalse(tool.execute(experiment, "3", Map.of("command", "python script.py")).success());
        assertFalse(tool.execute(experiment, "4", Map.of("command", "git checkout -- service.txt")).success());
        assertFalse(tool.execute(experiment, "5", Map.of("command", "git config user.name pico")).success());
        assertFalse(tool.execute(experiment, "6", Map.of("command", "type \"..\\outside.txt\"")).success());
        assertFalse(tool.execute(experiment, "7", Map.of("command", "cat '../outside.txt'")).success());
        assertFalse(tool.execute(experiment, "8", Map.of("command", "cat $HOME/.ssh/id_rsa")).success());
        assertFalse(tool.execute(experiment, "9", Map.of("command", "cat ${HOME}/.ssh/id_rsa")).success());
        assertFalse(tool.execute(experiment, "10", Map.of("command", "cat ~/.ssh/id_rsa")).success());
    }

    @Test
    void cancelledCommandStillPersistsEvidenceAgainstAnObservedSnapshot() throws Exception {
        Path canonical = temp.resolve("canonical");
        Files.createDirectories(canonical);
        run(canonical, "git", "init", "-q");
        run(canonical, "git", "config", "user.email", "pico-test@example.invalid");
        run(canonical, "git", "config", "user.name", "PICO Test");
        Files.writeString(canonical.resolve("service.txt"), "base\n");
        run(canonical, "git", "add", "service.txt");
        run(canonical, "git", "commit", "-qm", "initial");
        ProcessRunner runner = new ProcessRunner();
        GitSnapshotAdapter snapshots = new GitSnapshotAdapter(runner, temp.resolve("data").toString());
        InMemorySnapshotRepository snapshotRepository = new InMemorySnapshotRepository();
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        InMemoryEvidenceRepository evidence = new InMemoryEvidenceRepository();
        Project project = projects.save(Project.create("demo", canonical, List.of("java -version"), Instant.now()));
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

    private void run(Path cwd, String... command) {
        ProcessRunner.ProcessResult result = new ProcessRunner().run(List.of(command), cwd, Map.of(), java.time.Duration.ofSeconds(20));
        if (result.exitCode() != 0) throw new AssertionError(result.stderr());
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
