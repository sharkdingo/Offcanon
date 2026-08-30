package com.offcanon.infrastructure.memory;

import com.offcanon.agent.domain.RunEvent;
import com.offcanon.port.EventSink;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
public class InMemoryEventSink implements EventSink {
    private static final int DEFAULT_RETENTION = 2_000;
    private final ConcurrentHashMap<UUID, EventBuffer> buffers = new ConcurrentHashMap<>();
    private final int retention;
    public InMemoryEventSink(int retention) {
        this.retention = Math.max(100, retention);
    }

    public InMemoryEventSink() {
        this(DEFAULT_RETENTION);
    }

    @Override
    public RunEvent publish(UUID experimentId, String type, Map<String, Object> payload) {
        EventBuffer buffer = buffers.computeIfAbsent(experimentId, ignored -> new EventBuffer());
        synchronized (buffer) {
            long sequence = ++buffer.lastSequence;
            RunEvent event = new RunEvent(UUID.randomUUID(), experimentId, sequence, type, Instant.now(), payload);
            buffer.events.addLast(event);
            while (buffer.events.size() > retention) {
                buffer.events.removeFirst();
            }
            return event;
        }
    }

    @Override
    public List<RunEvent> after(UUID experimentId, long sequence) {
        EventBuffer buffer = buffers.get(experimentId);
        if (buffer == null) return List.of();
        synchronized (buffer) {
            ArrayList<RunEvent> pending = new ArrayList<>();
            for (RunEvent event : buffer.events) {
                if (event.sequence() > sequence) pending.add(event);
            }
            return List.copyOf(pending);
        }
    }

    private static final class EventBuffer {
        private final ArrayDeque<RunEvent> events = new ArrayDeque<>();
        private long lastSequence;
    }
}
