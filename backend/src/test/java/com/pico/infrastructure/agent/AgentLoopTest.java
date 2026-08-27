package com.pico.infrastructure.agent;

import com.pico.agent.domain.AgentRunResult;
import com.pico.agent.domain.ModelRequest;
import com.pico.agent.domain.ModelResponse;
import com.pico.agent.domain.ToolCall;
import com.pico.agent.domain.SessionContext;
import com.pico.experiment.domain.Experiment;
import com.pico.infrastructure.process.ProcessRunner;
import com.pico.infrastructure.memory.InMemoryEventSink;
import com.pico.port.ModelPort;
import com.pico.port.Tool;
import com.pico.shared.domain.DomainException;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopTest {
    @TempDir
    Path temp;

    @Test
    void executesToolsSequentiallyAndStopsOnFinalModelMessage() throws Exception {
        WorkspacePathResolver paths = new WorkspacePathResolver();
        ProcessRunner runner = new ProcessRunner();
        List<Tool> toolList = List.of(
                new ReadFileTool(paths),
                new WriteFileTool(paths),
                new ListFilesTool(paths),
                new SearchFilesTool(paths),
                new ShellTool(runner, 5));
        ToolRegistryImpl registry = new ToolRegistryImpl(toolList);
        Queue<ModelResponse> responses = new ArrayDeque<>();
        responses.add(new ModelResponse("", List.of(new ToolCall("1", "write_file", Map.of("path", "hello.txt", "content", "hello"))), "tool_calls"));
        responses.add(new ModelResponse("", List.of(new ToolCall("2", "shell", Map.of("command", "type hello.txt"))), "tool_calls"));
        responses.add(new ModelResponse("Updated and verified.", List.of(), "stop"));

        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "write hello.txt and verify it", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), temp);
        AgentRunResult result = new AgentLoop(new QueueModel(responses), registry, 5).run(experiment, new NoCancellation());

        assertEquals("Updated and verified.", result.summary());
        assertEquals(3, result.steps());
        assertEquals("hello", Files.readString(temp.resolve("hello.txt")));
        assertTrue(result.context().stream().anyMatch(message -> message.role().name().equals("TOOL") && message.content().contains("exit=0")));
    }

    @Test
    void retriesOnlyTransientModelFailuresAndPersistsContextSnapshotEvents() {
        AtomicInteger calls = new AtomicInteger();
        ModelPort model = request -> {
            if (calls.getAndIncrement() == 0) {
                throw new DomainException("MODEL_TRANSIENT_FAILURE", "retry me");
            }
            return new ModelResponse("done", List.of(), "stop");
        };
        InMemoryEventSink events = new InMemoryEventSink();
        Experiment experiment = experiment(temp);

        AgentRunResult result = new AgentLoop(model, new ToolRegistryImpl(List.of()), 3, events,
                20_000, 2, Duration.ofSeconds(5)).run(experiment, new NoCancellation());

        assertEquals("done", result.summary());
        assertEquals(2, calls.get());
        assertTrue(events.after(experiment.id(), 0).stream().anyMatch(event -> event.type().equals("CONTEXT_SNAPSHOT")
                && event.payload().containsKey("contextHash") && event.payload().containsKey("messages")));
        assertTrue(events.after(experiment.id(), 0).stream().anyMatch(event -> event.type().equals("MODEL_RETRY")));
    }

    @Test
    void carriesOnlyProvenanceMarkedIntentAndSummaryIntoANewSnapshot() {
        AtomicReference<ModelRequest> captured = new AtomicReference<>();
        ModelPort model = request -> {
            captured.set(request);
            return new ModelResponse("done", List.of(), "stop");
        };
        InMemoryEventSink events = new InMemoryEventSink();
        Experiment experiment = experiment(temp);
        SessionContext prior = new SessionContext(UUID.randomUUID(), UUID.randomUUID(),
                "keep the public API stable", "renamed the adapter; re-check the build");

        new AgentLoop(model, new ToolRegistryImpl(List.of()), 3, events,
                20_000, 1, Duration.ofSeconds(5))
                .run(experiment, new NoCancellation(), Optional.of(prior));

        String prompt = captured.get().messages().get(1).content();
        assertTrue(prompt.contains("historical context, not current filesystem fact"));
        assertTrue(prompt.contains("stale reasoning; re-check against the current snapshot"));
        assertTrue(prompt.contains("Prior filesystem observations and tool results were intentionally excluded"));
        assertTrue(prompt.contains("keep the public API stable"));
        assertTrue(prompt.contains("renamed the adapter"));
        assertTrue(events.after(experiment.id(), 0).stream().anyMatch(event ->
                event.type().equals("SESSION_CONTEXT_IMPORTED")
                        && event.payload().get("priorSnapshotId").equals(prior.priorSnapshotId().toString())
                        && event.payload().get("excluded").toString().contains("TOOL_RESULTS")));
    }

    @Test
    void boundsImportedSessionSummaryInsideTheFixedContextMessages() {
        AtomicReference<ModelRequest> captured = new AtomicReference<>();
        ModelPort model = request -> {
            captured.set(request);
            return new ModelResponse("done", List.of(), "stop");
        };
        int contextLimit = 8_000;
        SessionContext prior = new SessionContext(UUID.randomUUID(), UUID.randomUUID(),
                "prior intent ".repeat(4_000), "prior summary ".repeat(10_000));

        new AgentLoop(model, new ToolRegistryImpl(List.of()), 2, new InMemoryEventSink(),
                contextLimit, 1, Duration.ofSeconds(5))
                .run(experiment(temp), new NoCancellation(), Optional.of(prior));

        int fixedChars = captured.get().messages().stream().mapToInt(message -> message.content().length()).sum();
        assertTrue(fixedChars < contextLimit);
        assertTrue(captured.get().messages().get(1).content().contains("...[truncated]..."));
    }

    @Test
    void enforcesOverallRunDeadline() {
        ModelPort slow = request -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            return new ModelResponse("late", List.of(), "stop");
        };

        DomainException error = assertThrows(DomainException.class, () -> new AgentLoop(slow,
                new ToolRegistryImpl(List.of()), 2, new InMemoryEventSink(), 20_000, 1,
                Duration.ofMillis(40)).run(experiment(temp), new NoCancellation()));

        assertEquals("AGENT_TIMEOUT", error.code());
    }

    @Test
    void stopsAfterRepeatedInvalidToolArguments() {
        Queue<ModelResponse> responses = new ArrayDeque<>();
        for (int index = 0; index < 3; index++) {
            responses.add(new ModelResponse("", List.of(new ToolCall("bad-" + index, "write_file",
                    Map.of("path", "file.txt"))), "tool_calls"));
        }
        ToolRegistryImpl registry = new ToolRegistryImpl(List.of(new WriteFileTool(new WorkspacePathResolver())));

        DomainException error = assertThrows(DomainException.class, () -> new AgentLoop(
                new QueueModel(responses), registry, 5).run(experiment(temp), new NoCancellation()));

        assertEquals("REPEATED_TOOL_FAILURE", error.code());
    }

    @Test
    void stopsAtMaxStepsWhenModelNeverFinishes() {
        AtomicInteger calls = new AtomicInteger();
        ModelPort model = request -> new ModelResponse("", List.of(new ToolCall(
                "unknown-" + calls.incrementAndGet(), "missing_tool", Map.of("attempt", calls.get()))), "tool_calls");

        DomainException error = assertThrows(DomainException.class, () -> new AgentLoop(model,
                new ToolRegistryImpl(List.of()), 2).run(experiment(temp), new NoCancellation()));

        assertEquals("MAX_STEPS_EXCEEDED", error.code());
    }

    private Experiment experiment(Path workspace) {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), workspace);
        return experiment;
    }

    private static final class QueueModel implements ModelPort {
        private final Queue<ModelResponse> responses;

        private QueueModel(Queue<ModelResponse> responses) {
            this.responses = responses;
        }

        @Override
        public ModelResponse complete(ModelRequest request) {
            ModelResponse response = responses.poll();
            if (response == null) throw new AssertionError("No scripted response remaining");
            return response;
        }
    }
}
