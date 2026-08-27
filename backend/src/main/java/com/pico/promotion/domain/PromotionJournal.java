package com.pico.promotion.domain;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.List;
import java.util.Map;

/** Durable intent and outcome for a multi-file canonical promotion. */
public record PromotionJournal(
        UUID promotionId,
        UUID experimentId,
        UUID projectId,
        String baseFingerprint,
        String candidateFingerprint,
        Path candidatePath,
        List<String> touchedFiles,
        Map<String, String> preimageHashes,
        Map<String, String> postimageHashes,
        PromotionPhase phase,
        String ownerId,
        Instant leaseUntil,
        Instant createdAt,
        Instant updatedAt,
        String resultingFingerprint,
        String failureReason,
        long version) {

    public PromotionJournal {
        Objects.requireNonNull(promotionId, "promotionId");
        Objects.requireNonNull(experimentId, "experimentId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(baseFingerprint, "baseFingerprint");
        Objects.requireNonNull(candidateFingerprint, "candidateFingerprint");
        Objects.requireNonNull(candidatePath, "candidatePath");
        Objects.requireNonNull(touchedFiles, "touchedFiles");
        Objects.requireNonNull(preimageHashes, "preimageHashes");
        Objects.requireNonNull(postimageHashes, "postimageHashes");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        candidatePath = candidatePath.toAbsolutePath().normalize();
        touchedFiles = List.copyOf(touchedFiles);
        preimageHashes = Map.copyOf(preimageHashes);
        postimageHashes = Map.copyOf(postimageHashes);
        var touched = java.util.Set.copyOf(touchedFiles);
        if (!preimageHashes.keySet().equals(touched) || !postimageHashes.keySet().equals(touched)) {
            throw new IllegalArgumentException("Every touched file must have exactly one preimage and postimage hash");
        }
    }

    public static PromotionJournal create(UUID experimentId,
                                          UUID projectId,
                                          String baseFingerprint,
                                          String candidateFingerprint,
                                          Path candidatePath,
                                          List<String> touchedFiles,
                                          Map<String, String> preimageHashes,
                                          Map<String, String> postimageHashes,
                                          String ownerId,
                                          Instant now,
                                          Instant leaseUntil) {
        return new PromotionJournal(UUID.randomUUID(), experimentId, projectId, baseFingerprint,
                candidateFingerprint, candidatePath, touchedFiles, preimageHashes, postimageHashes,
                PromotionPhase.PREPARED, ownerId,
                leaseUntil, now, now, null, null, 0);
    }

    public static PromotionJournal create(UUID experimentId,
                                          UUID projectId,
                                          String baseFingerprint,
                                          String candidateFingerprint,
                                          Path candidatePath,
                                          String ownerId,
                                          Instant now,
                                          Instant leaseUntil) {
        return create(experimentId, projectId, baseFingerprint, candidateFingerprint, candidatePath,
                List.of(), Map.of(), Map.of(), ownerId, now, leaseUntil);
    }

    public PromotionJournal transitioned(PromotionPhase next, Instant now, String result, String failure) {
        boolean allowed = switch (phase) {
            case PREPARED -> next == PromotionPhase.APPLYING
                    || next == PromotionPhase.ABORTED
                    || next == PromotionPhase.RECOVERY_REQUIRED;
            case APPLYING -> next == PromotionPhase.COMMITTED
                    || next == PromotionPhase.ABORTED
                    || next == PromotionPhase.RECOVERY_REQUIRED;
            case COMMITTED, ABORTED, RECOVERY_REQUIRED -> false;
        };
        if (!allowed) {
            throw new IllegalStateException("Invalid promotion journal transition: " + phase + " -> " + next);
        }
        return new PromotionJournal(promotionId, experimentId, projectId, baseFingerprint,
                candidateFingerprint, candidatePath, touchedFiles, preimageHashes, postimageHashes,
                next, ownerId, leaseUntil, createdAt,
                now, result, failure, version + 1);
    }

    public PromotionJournal claimed(String newOwnerId, Instant now, Instant newLeaseUntil) {
        if (phase != PromotionPhase.PREPARED && phase != PromotionPhase.APPLYING) {
            throw new IllegalStateException("Terminal promotion journal cannot be claimed");
        }
        if (leaseUntil.isAfter(now)) {
            throw new IllegalStateException("Active promotion journal cannot be claimed");
        }
        if (!newLeaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("Claim lease must end in the future");
        }
        return new PromotionJournal(promotionId, experimentId, projectId, baseFingerprint,
                candidateFingerprint, candidatePath, touchedFiles, preimageHashes, postimageHashes,
                phase, Objects.requireNonNull(newOwnerId, "newOwnerId"),
                newLeaseUntil, createdAt, now, resultingFingerprint, failureReason, version + 1);
    }

    /**
     * Closes a manually inspected recovery journal without reusing the expired
     * worker lease. The caller must independently prove canonical state first.
     */
    public PromotionJournal reconciled(PromotionPhase outcome,
                                       Instant now,
                                       String result,
                                       String failure) {
        if (phase != PromotionPhase.RECOVERY_REQUIRED
                || (outcome != PromotionPhase.COMMITTED && outcome != PromotionPhase.ABORTED)) {
            throw new IllegalStateException("Recovery journal can only reconcile to COMMITTED or ABORTED");
        }
        return new PromotionJournal(promotionId, experimentId, projectId, baseFingerprint,
                candidateFingerprint, candidatePath, touchedFiles, preimageHashes, postimageHashes,
                outcome, ownerId, leaseUntil, createdAt, now, result, failure, version + 1);
    }
}
