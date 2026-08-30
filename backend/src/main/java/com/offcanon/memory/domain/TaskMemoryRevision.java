package com.offcanon.memory.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable, provenance-bearing statement in a Session memory ledger.
 * Revisions are never updated in place; a later revision may supersede one.
 */
public record TaskMemoryRevision(
        UUID id,
        UUID projectId,
        UUID sessionId,
        UUID sourceExperimentId,
        UUID sourceSnapshotId,
        String sourceFingerprint,
        TaskMemoryKind kind,
        String content,
        List<UUID> sourceEvidenceIds,
        TaskMemoryOrigin origin,
        TaskMemoryTrust trust,
        TaskMemoryStatus status,
        List<UUID> supersedesIds,
        Instant createdAt,
        long sequence) {

    public static final int MAX_CONTENT_CHARS = 8_000;
    public static final int MAX_EVIDENCE_REFERENCES = 64;

    public TaskMemoryRevision {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(sourceExperimentId, "sourceExperimentId");
        Objects.requireNonNull(sourceSnapshotId, "sourceSnapshotId");
        Objects.requireNonNull(sourceFingerprint, "sourceFingerprint");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(sourceEvidenceIds, "sourceEvidenceIds");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(trust, "trust");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(supersedesIds, "supersedesIds");
        Objects.requireNonNull(createdAt, "createdAt");
        sourceFingerprint = sourceFingerprint.trim();
        content = content.trim();
        sourceEvidenceIds = List.copyOf(sourceEvidenceIds);
        supersedesIds = List.copyOf(supersedesIds);
        if (sourceFingerprint.isBlank()) throw new IllegalArgumentException("Memory source fingerprint must not be blank");
        if (content.isBlank()) throw new IllegalArgumentException("Memory content must not be blank");
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException("Memory content exceeds " + MAX_CONTENT_CHARS + " characters");
        }
        if (sourceEvidenceIds.size() > MAX_EVIDENCE_REFERENCES) {
            throw new IllegalArgumentException("Memory has too many evidence references");
        }
        if (sourceEvidenceIds.stream().distinct().count() != sourceEvidenceIds.size()) {
            throw new IllegalArgumentException("Memory evidence references must be unique");
        }
        if (sequence < 1) throw new IllegalArgumentException("Memory sequence must be positive");
        if (supersedesIds.stream().distinct().count() != supersedesIds.size()) {
            throw new IllegalArgumentException("Superseded memory references must be unique");
        }
        if (supersedesIds.contains(id)) {
            throw new IllegalArgumentException("Memory revision cannot supersede itself");
        }
        if (kind == TaskMemoryKind.VERIFIED_FACT
                && (origin != TaskMemoryOrigin.VERIFIED_SYSTEM
                || !trust.isTrustedFactSource() || status != TaskMemoryStatus.ACCEPTED)) {
            throw new IllegalArgumentException("Verified facts require trusted provenance and ACCEPTED status");
        }
        if (origin == TaskMemoryOrigin.AGENT_REPORTED
                && (trust != TaskMemoryTrust.AGENT_REPORTED || status != TaskMemoryStatus.PROPOSED)) {
            throw new IllegalArgumentException("Agent-authored memory must remain an AGENT_REPORTED proposal");
        }
        if (origin == TaskMemoryOrigin.USER_AUTHORED && trust != TaskMemoryTrust.USER_CONFIRMED) {
            throw new IllegalArgumentException("User-authored memory must use USER_CONFIRMED trust");
        }
        if (origin == TaskMemoryOrigin.VERIFIED_SYSTEM && !trust.isTrustedFactSource()) {
            throw new IllegalArgumentException("System-verified memory requires trusted provenance");
        }
    }

    public boolean appliesTo(String currentFingerprint) {
        return sourceFingerprint.equals(Objects.requireNonNull(currentFingerprint, "currentFingerprint"));
    }
}
