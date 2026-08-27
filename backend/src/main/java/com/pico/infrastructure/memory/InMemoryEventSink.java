package com.pico.infrastructure.memory;

import com.pico.agent.domain.RunEvent;
import com.pico.port.EventSink;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Profile("!mysql")
public class InMemoryEventSink implements EventSink {
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<RunEvent>> events = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();

    @Override
    public RunEvent publish(UUID experimentId, String type, Map<String, Object> payload) {
        synchronized (locks.computeIfAbsent(experimentId, ignored -> new Object())) {
            long sequence = sequences.computeIfAbsent(experimentId, ignored -> new AtomicLong()).incrementAndGet();
            RunEvent event = new RunEvent(UUID.randomUUID(), experimentId, sequence, type, Instant.now(), payload);
            events.computeIfAbsent(experimentId, ignored -> new CopyOnWriteArrayList<>()).add(event);
            return event;
        }
    }

    @Override
    public List<RunEvent> after(UUID experimentId, long sequence) {
        synchronized (locks.computeIfAbsent(experimentId, ignored -> new Object())) {
            return events.getOrDefault(experimentId, new CopyOnWriteArrayList<>()).stream()
                    .filter(event -> event.sequence() > sequence)
                    .sorted(java.util.Comparator.comparingLong(RunEvent::sequence))
                    .toList();
        }
    }
}
