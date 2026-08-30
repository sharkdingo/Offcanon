package com.offcanon.memory.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Validated input proposed by an Agent or produced by deterministic application code. */
public record MemoryPatch(
        TaskMemoryKind kind,
        String content,
        List<UUID> sourceEvidenceIds,
        List<UUID> supersedesIds) {

    public MemoryPatch {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(sourceEvidenceIds, "sourceEvidenceIds");
        Objects.requireNonNull(supersedesIds, "supersedesIds");
        content = content.trim();
        sourceEvidenceIds = List.copyOf(sourceEvidenceIds);
        supersedesIds = List.copyOf(supersedesIds);
        if (content.isBlank()) throw new IllegalArgumentException("Memory patch content must not be blank");
        if (content.length() > TaskMemoryRevision.MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException("Memory patch content exceeds "
                    + TaskMemoryRevision.MAX_CONTENT_CHARS + " characters");
        }
        if (sourceEvidenceIds.size() > TaskMemoryRevision.MAX_EVIDENCE_REFERENCES) {
            throw new IllegalArgumentException("Memory patch has too many evidence references");
        }
        if (sourceEvidenceIds.stream().distinct().count() != sourceEvidenceIds.size()) {
            throw new IllegalArgumentException("Memory patch evidence references must be unique");
        }
        if (supersedesIds.stream().distinct().count() != supersedesIds.size()) {
            throw new IllegalArgumentException("Memory patch superseded references must be unique");
        }
    }

    public static MemoryPatch of(TaskMemoryKind kind, String content) {
        return new MemoryPatch(kind, content, List.of(), List.of());
    }
}
