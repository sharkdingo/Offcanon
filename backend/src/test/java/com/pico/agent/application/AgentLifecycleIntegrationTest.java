package com.pico.agent.application;

import com.pico.agent.domain.ModelRequest;
import com.pico.agent.domain.ModelResponse;
import com.pico.agent.domain.ToolCall;
import com.pico.application.ExperimentApplicationService;
import com.pico.experiment.domain.Experiment;
import com.pico.experiment.domain.ExperimentStatus;
import com.pico.infrastructure.agent.AgentLoop;
import com.pico.infrastructure.agent.ListFilesTool;
import com.pico.infrastructure.agent.ReadFileTool;
import com.pico.infrastructure.agent.SearchFilesTool;
import com.pico.infrastructure.agent.ShellTool;
import com.pico.infrastructure.agent.ToolRegistryImpl;
import com.pico.infrastructure.agent.WorkspacePathResolver;
import com.pico.infrastructure.agent.WriteFileTool;
import com.pico.infrastructure.git.GitSnapshotAdapter;
import com.pico.infrastructure.memory.InMemoryEventSink;
import com.pico.infrastructure.memory.InMemoryEvidenceRepository;
import com.pico.infrastructure.memory.InMemoryExperimentRepository;
import com.pico.infrastructure.memory.InMemoryProjectRepository;
import com.pico.infrastructure.memory.InMemoryPromotionLock;
import com.pico.infrastructure.memory.InMemoryPromotionJournal;
import com.pico.infrastructure.memory.InMemorySessionRepository;
import com.pico.infrastructure.memory.InMemorySessionRunLease;
import com.pico.infrastructure.memory.InMemorySnapshotRepository;
import com.pico.infrastructure.process.LocalCommandExecutor;
import com.pico.infrastructure.process.ProcessRunner;
import com.pico.infrastructure.promotion.LocalPromotionAdapter;
import com.pico.infrastructure.system.SystemClock;
import com.pico.infrastructure.verification.TrustedVerificationAdapter;
import com.pico.infrastructure.workspace.LocalWorkspaceAdapter;
import com.pico.port.AgentLoopPort;
import com.pico.port.ModelPort;
import com.pico.project.domain.Project;
import com.pico.promotion.application.PromotionApplicationService;
import com.pico.shared.domain.DomainException;
import com.pico.verification.domain.VerificationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLifecycleIntegrationTest {
    @TempDir
    Path temp;

    @Test
    void runsAgentVerifyAndPromoteWithoutTouchingCanonicalEarly() throws Exception {
        Path canonical = temp.resolve("canonical");
        Files.createDirectories(canonical);
        run(canonical, "git", "init", "-q");
        run(canonical, "git", "config", "user.email", "pico-test@example.invalid");
        run(canonical, "git", "config", "user.name", "PICO Test");
        Files.writeString(canonical.resolve("service.txt"), "base\n");
        Files.writeString(canonical.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>demo</groupId><artifactId>fixture</artifactId><version>1.0.0</version>
                  <properties><maven.compiler.release>17</maven.compiler.release></properties>
                </project>
                """);
        Path source = canonical.resolve("src/main/java/demo/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo; public final class App { private App() {} }\n");
        run(canonical, "git", "add", "service.txt", "pom.xml", "src/main/java/demo/App.java");
        run(canonical, "git", "commit", "-qm", "initial");

        ProcessRunner runner = new ProcessRunner();
        Path data = temp.resolve("data");
        GitSnapshotAdapter snapshots = new GitSnapshotAdapter(runner, data.toString());
        LocalWorkspaceAdapter workspaces = new LocalWorkspaceAdapter(data.toString());
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        InMemorySnapshotRepository snapshotRepository = new InMemorySnapshotRepository();
        InMemoryEvidenceRepository evidence = new InMemoryEvidenceRepository();
        InMemoryEventSink events = new InMemoryEventSink();
        Project project = projects.save(Project.create("demo", canonical, List.of("mvn -q test"), Instant.now()));
        ExperimentApplicationService experimentService = new ExperimentApplicationService(projects, sessions,
                experiments, snapshotRepository, snapshots, workspaces, new SystemClock());
        Experiment experiment = experimentService.create(project.id(), null, "demo session", "update service");

        WorkspacePathResolver paths = new WorkspacePathResolver();
        ToolRegistryImpl registry = new ToolRegistryImpl(List.of(
                new ReadFileTool(paths), new WriteFileTool(paths), new ListFilesTool(paths),
                new SearchFilesTool(paths), new ShellTool(runner, projects, evidence, snapshots, snapshotRepository, 10)));
        AgentLoop agentLoop = new AgentLoop(new ScriptedModel(), registry, 5, events, 80_000);
        TrustedVerificationAdapter verification = new TrustedVerificationAdapter(
                new LocalCommandExecutor(runner), evidence, snapshots, snapshotRepository, 30);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AgentApplicationService agent = new AgentApplicationService(experiments, projects, snapshotRepository,
                snapshots, agentLoop, verification, executor, events, new InMemorySessionRunLease(), workspaces);
        PromotionApplicationService promotion = new PromotionApplicationService(experiments, projects,
                snapshotRepository, snapshots, workspaces, new LocalPromotionAdapter(),
                new InMemoryPromotionLock(), events, verification, new InMemoryPromotionJournal());
        try {
            agent.start(experiment.id());
            waitFor(experiments, experiment.id(), ExperimentStatus.VERIFIED);
            Experiment verified = experiments.findById(experiment.id()).orElseThrow();

            assertEquals("base\n", normalized(canonical.resolve("service.txt")));
            assertNotNull(verified.resultSnapshotId());
            assertTrue(Files.exists(verified.workspacePath().resolve("service.txt")));
            var commandEvidence = evidence.findByExperimentId(experiment.id()).stream()
                    .filter(item -> item.kind().equals("AGENT_COMMAND"))
                    .findFirst().orElseThrow();
            assertEquals("agent-shell", commandEvidence.environmentProfile());
            assertEquals("agent result\n", normalized(snapshotRepository.findById(commandEvidence.snapshotId())
                    .orElseThrow().materializedPath().resolve("service.txt")));
            Path sealedResult = snapshotRepository.findById(verified.resultSnapshotId()).orElseThrow().materializedPath();
            assertTrue(Files.notExists(sealedResult.resolve("target")), "trusted verification mutated the sealed result");
            var trustedEvidence = evidence.findByExperimentId(experiment.id()).stream()
                    .filter(item -> item.kind().equals("VERIFICATION"))
                    .findFirst().orElseThrow();
            assertTrue(Files.isDirectory(Path.of(trustedEvidence.cwd()).resolve("target")),
                    "Maven verification did not execute in its disposable workspace");

            PromotionApplicationService.PromotionOutcome outcome = promotion.promote(experiment.id());

            assertTrue(outcome.promoted(), outcome.toString());
            assertEquals("agent result\n", normalized(canonical.resolve("service.txt")));
            assertTrue(Files.notExists(canonical.resolve("target")), "build output leaked into canonical");
        } finally {
            executor.shutdownNow();
        }
    }

    private void waitFor(InMemoryExperimentRepository experiments, UUID id, ExperimentStatus expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        ExperimentStatus last = null;
        while (System.nanoTime() < deadline) {
            Experiment current = experiments.findById(id).orElseThrow();
            last = current.status();
            if (last == expected) return;
            if (last == ExperimentStatus.FAILED || last == ExperimentStatus.REJECTED) {
                throw new AssertionError("Agent did not reach expected state: " + current.failureReason());
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for " + expected + ", last state was " + last);
    }

    private String normalized(Path path) throws Exception {
        return Files.readString(path).replace("\r\n", "\n");
    }

    private void run(Path cwd, String... command) {
        ProcessRunner.ProcessResult result = new ProcessRunner().run(List.of(command), cwd, Map.of(), Duration.ofSeconds(20));
        if (result.exitCode() != 0) throw new AssertionError(result.stderr());
    }

    private static final class ScriptedModel implements ModelPort {
        private final Queue<ModelResponse> responses = new ArrayDeque<>(List.of(
                new ModelResponse("", List.of(new ToolCall("write", "write_file", Map.of(
                        "path", "service.txt", "content", "agent result\n"))), "tool_calls"),
                new ModelResponse("", List.of(new ToolCall("shell", "shell", Map.of(
                        "command", "type service.txt"))), "tool_calls"),
                new ModelResponse("changed and checked", List.of(), "stop")));

        @Override
        public ModelResponse complete(ModelRequest request) {
            ModelResponse response = responses.poll();
            if (response == null) throw new DomainException("SCRIPT_EXHAUSTED", "Scripted model exhausted");
            return response;
        }
    }
}
