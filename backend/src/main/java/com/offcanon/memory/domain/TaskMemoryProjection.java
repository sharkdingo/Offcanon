package com.offcanon.memory.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Deterministic read model for one Session at one current Snapshot fingerprint. */
public record TaskMemoryProjection(
        UUID projectId,
        UUID sessionId,
        String currentFingerprint,
        List<ProjectedMemory> current,
        List<ProjectedMemory> stale,
        List<ProjectedMemory> proposed,
        List<ProjectedMemory> conflicted) {

    public TaskMemoryProjection {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(currentFingerprint, "currentFingerprint");
        current = List.copyOf(current);
        stale = List.copyOf(stale);
        proposed = List.copyOf(proposed);
        conflicted = List.copyOf(conflicted);
    }

    public record ProjectedMemory(TaskMemoryRevision revision, Freshness freshness) {
        public ProjectedMemory {
            Objects.requireNonNull(revision, "revision");
            Objects.requireNonNull(freshness, "freshness");
        }
    }

    public enum Freshness {
        CURRENT,
        STALE
    }
}
