package com.offcanon.infrastructure.agent;

import com.offcanon.agent.domain.ModelMessage;
import com.offcanon.agent.domain.ToolCall;
import com.offcanon.agent.domain.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextManagerTest {
    @Test
    void truncationMarkerIsIncludedInTheConfiguredLimit() {
        String value = "0123456789".repeat(20);
        for (int limit = 1; limit <= 100; limit++) {
            String truncated = ContextManager.truncate(value, limit);
            assertTrue(truncated.length() <= limit, "limit=" + limit);
        }
        assertTrue(ContextManager.truncate(value, 40).contains("...[truncated]..."));
        assertTrue(ContextManager.truncateObservation(value, 40).contains("context truncated"));
    }

    @Test
    void stableJsonAndHashDoNotDependOnMapInsertionOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("z", List.of(1, 2));
        first.put("a", Map.of("right", true, "left", "x"));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", Map.of("left", "x", "right", true));
        second.put("z", List.of(1, 2));

        assertEquals(ContextManager.stableJson(first), ContextManager.stableJson(second));
        ToolCall firstCall = new ToolCall("call", "inspect", first);
        ToolCall secondCall = new ToolCall("call", "inspect", second);
        List<ModelMessage> firstMessages = List.of(ModelMessage.user("task"),
                ModelMessage.assistant("", List.of(firstCall)));
        List<ModelMessage> secondMessages = List.of(ModelMessage.user("task"),
                ModelMessage.assistant("", List.of(secondCall)));
        List<ToolDefinition> definitions = List.of(new ToolDefinition("inspect", "Inspect", Map.of("type", "object")));
        assertEquals(ContextManager.contextHash(firstMessages, definitions),
                ContextManager.contextHash(secondMessages, definitions));
    }

    @Test
    void rollingCompactionKeepsLatestTurnAndReportsItsIdentity() {
        ContextManager manager = new ContextManager(List.of(), 1_800);
        manager.add(ModelMessage.system("policy"));
        manager.add(ModelMessage.user("current task"));
        manager.add(ModelMessage.assistant("old plan ".repeat(120),
                List.of(new ToolCall("old-call", "read_file", Map.of("path", "old.txt")))));
        manager.add(ModelMessage.tool("old-call", "read_file", "old observation ".repeat(120)));
        manager.add(ModelMessage.assistant("latest plan ".repeat(40),
                List.of(new ToolCall("latest-call", "read_file", Map.of("path", "new.txt")))));
        manager.add(ModelMessage.tool("latest-call", "read_file", "latest observation"));

        ContextManager.CompactionReport report = manager.ensureWithinBudget();

        assertTrue(report.compacted());
        assertTrue(report.removedMessages() >= 2);
        assertFalse(report.removedTurnDetails().isEmpty());
        assertTrue(report.removedTurnDetails().stream().anyMatch(turn ->
                turn.toolCallIds().contains("old-call") && !turn.toolCallIds().contains("latest-call")));
        assertTrue(report.rollingSummary().contains(ContextManager.ROLLING_SUMMARY_HEADER));
        assertTrue(report.rollingSummary().contains("untrusted data"));
        assertTrue(manager.contextChars() <= 1_800);
        assertTrue(manager.messages().stream().flatMap(message -> message.toolCalls().stream())
                .anyMatch(call -> call.id().equals("latest-call")));
    }

    @Test
    void refusesWhenTheFixedAndLatestContextCannotFit() {
        ContextManager manager = new ContextManager(List.of(), 100);
        manager.add(ModelMessage.system("policy"));
        manager.add(ModelMessage.user("task"));
        manager.add(ModelMessage.assistant("latest".repeat(100), List.of()));

        assertThrows(IllegalStateException.class, manager::ensureWithinBudget);
    }
}
