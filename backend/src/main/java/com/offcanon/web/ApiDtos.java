package com.offcanon.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ApiDtos {
    private ApiDtos() {
    }

    public record CreateProjectRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 4_096) String canonicalPath,
            // The application service validates the non-empty policy for a
            // genuinely new project. A repeated open may intentionally omit
            // commands because the existing project's policy is retained.
            @Size(max = 20) List<@NotBlank @Size(max = 1_000) String> verificationCommands) {
        public CreateProjectRequest {
            verificationCommands = verificationCommands == null ? List.of() : List.copyOf(verificationCommands);
        }
    }

    public record UpdateProjectRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 4_096) String canonicalPath,
            @Size(min = 1, max = 20) List<@NotBlank @Size(max = 1_000) String> verificationCommands) {
        public UpdateProjectRequest {
            verificationCommands = verificationCommands == null ? List.of() : List.copyOf(verificationCommands);
        }
    }

    public record CreateExperimentRequest(UUID sessionId,
                                          @Size(max = 200) String sessionTitle,
                                          @NotBlank @Size(max = 20_000) String task) {
    }

    public record ContinueExperimentRequest(@Size(max = 20_000) String task) {
    }

    public record CreateSessionRequest(@NotBlank @Size(max = 200) String title) {
    }

    public record ProjectResponse(UUID id, String name, String canonicalPath, List<String> verificationCommands, Instant createdAt) {
    }

    public record ProjectRegistrationResponse(UUID id,
                                              String name,
                                              String canonicalPath,
                                              List<String> verificationCommands,
                                              Instant createdAt,
                                              boolean reopened) {
    }

    public record DirectoryBrowseResponse(String path,
                                          String parent,
                                          List<DirectoryEntryResponse> entries,
                                          boolean truncated,
                                          String gitRoot,
                                          String suggestedName,
                                          List<String> suggestedVerificationCommands,
                                          List<DirectoryLocationResponse> locations) {
        public DirectoryBrowseResponse {
            entries = entries == null ? List.of() : List.copyOf(entries);
            suggestedVerificationCommands = suggestedVerificationCommands == null
                    ? List.of() : List.copyOf(suggestedVerificationCommands);
            locations = locations == null ? List.of() : List.copyOf(locations);
        }
    }

    public record DirectoryEntryResponse(String name, String path) {
    }

    public record DirectoryLocationResponse(String kind, String path) {
    }

    public record SessionResponse(UUID id, UUID projectId, String title, Instant createdAt) {
    }

    public record ExperimentResponse(
            UUID id,
            UUID projectId,
            UUID sessionId,
            UUID continuedFromExperimentId,
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

    public record PromotionStaleConfirmationResponse(
            boolean markedStale,
            String status,
            String detail,
            String currentFingerprint) {
    }

    public record PromotionPreviewResponse(
            String baseFingerprint,
            String currentFingerprint,
            String finalCandidateFingerprint,
            String verificationStatus,
            boolean trustedVerification,
            boolean conflict,
            String blockingReason,
            boolean promotable,
            boolean recoveryRequired,
            String recoveryJournalPhase,
            UUID recoveryPromotionId) {
        public PromotionPreviewResponse(String baseFingerprint,
                                         String currentFingerprint,
                                         String finalCandidateFingerprint,
                                         String verificationStatus,
                                         boolean trustedVerification,
                                         boolean conflict,
                                         String blockingReason,
                                         boolean promotable) {
            this(baseFingerprint, currentFingerprint, finalCandidateFingerprint, verificationStatus,
                    trustedVerification, conflict, blockingReason, promotable, false, null, null);
        }
    }

    public record PromotionReconcileResponse(
            UUID promotionId,
            String experimentStatus,
            String journalPhase,
            String fingerprint,
            String detail) {
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
