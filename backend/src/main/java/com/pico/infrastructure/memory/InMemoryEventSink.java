package com.pico.infrastructure.memory;

import com.pico.agent.domain.RunEvent;
import com.pico.port.EventSink;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class InMemoryEventSink implements EventSink {
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<RunEvent>> events = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AtomicLong> sequences = new ConcurrentHashMap<>();

    @Override
    public RunEvent publish(UUID experimentId, String type, Map<String, Object> payload) {
        long sequence = sequences.computeIfAbsent(experimentId, ignored -> new AtomicLong()).incrementAndGet();
        RunEvent event = new RunEvent(UUID.randomUUID(), experimentId, sequence, type, Instant.now(), payload);
        events.computeIfAbsent(experimentId, ignored -> new CopyOnWriteArrayList<>()).add(event);
        return event;
    }

    @Override
    public List<RunEvent> after(UUID experimentId, long sequence) {
        return events.getOrDefault(experimentId, new CopyOnWriteArrayList<>()).stream()
                .filter(event -> event.sequence() > sequence).toList();
    }
}
