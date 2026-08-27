package com.pico.infrastructure.agent;

import com.pico.agent.domain.AgentRunResult;
import com.pico.agent.domain.ModelRequest;
import com.pico.agent.domain.ModelResponse;
import com.pico.agent.domain.ToolCall;
import com.pico.experiment.domain.Experiment;
import com.pico.infrastructure.process.ProcessRunner;
import com.pico.infrastructure.system.SystemClock;
import com.pico.port.ModelPort;
import com.pico.port.Tool;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
