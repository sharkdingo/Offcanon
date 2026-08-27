package com.pico.session.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Session(UUID id, UUID projectId, String title, Instant createdAt, long version) {
    public Session {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(createdAt, "createdAt");
        if (title.isBlank()) {
            throw new IllegalArgumentException("Session title must not be blank");
        }
    }

    public static Session create(UUID projectId, String title, Instant now) {
        return new Session(UUID.randomUUID(), projectId, title.trim(), now, 0);
    }
}
