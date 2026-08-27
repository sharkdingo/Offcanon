package com.pico.agent.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record RunEvent(UUID eventId, UUID experimentId, long sequence, String type, Instant timestamp, Map<String, Object> payload) {
    public RunEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(experimentId, "experimentId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(timestamp, "timestamp");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
