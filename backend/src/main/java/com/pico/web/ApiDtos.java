package com.pico.web;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ApiDtos {
    private ApiDtos() {
    }

    public record CreateProjectRequest(
            @NotBlank String name,
            @NotBlank String canonicalPath,
            List<String> verificationCommands) {
        public CreateProjectRequest {
            verificationCommands = verificationCommands == null ? List.of() : List.copyOf(verificationCommands);
        }
    }

    public record CreateExperimentRequest(UUID sessionId, String sessionTitle, @NotBlank String task) {
    }

    public record CreateSessionRequest(@NotBlank String title) {
    }

    public record ProjectResponse(UUID id, String name, String canonicalPath, List<String> verificationCommands, Instant createdAt) {
    }

    public record SessionResponse(UUID id, UUID projectId, String title, Instant createdAt) {
    }

    public record ExperimentResponse(
            UUID id,
            UUID projectId,
            UUID sessionId,
            String task,
            String status,
            UUID baseSnapshotId,
            UUID resultSnapshotId,
            String workspacePath,
            String agentSummary,
            String failureReason,
            Instant createdAt,
            long version) {
    }

    public record EvidenceResponse(
            UUID id,
            UUID experimentId,
            UUID snapshotId,
            String kind,
            String command,
            String cwd,
            int exitCode,
            String stdout,
            String stderr,
            Instant startedAt,
            Instant completedAt,
            long durationMillis,
            boolean timedOut,
            boolean trusted,
            String environmentProfile,
            boolean cancelled) {
    }

    public record PromotionResponse(
            boolean promoted,
            String status,
            String detail,
            List<String> changedFiles,
            String fingerprint) {
    }

    public record PromotionPreviewResponse(
            String baseFingerprint,
            String currentFingerprint,
            String finalCandidateFingerprint,
            String verificationStatus,
            boolean trustedVerification,
            boolean conflict,
            String blockingReason,
            boolean promotable) {
    }

    public record DiffEntryResponse(String path,
                                    String change,
                                    long beforeBytes,
                                    long afterBytes,
                                    boolean binary,
                                    int additions,
                                    int deletions,
                                    String patch) {
    }
}
