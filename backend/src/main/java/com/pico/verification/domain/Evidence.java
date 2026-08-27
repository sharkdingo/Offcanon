package com.pico.verification.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Evidence(
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
        Duration duration,
        boolean timedOut,
        boolean trusted,
        String environmentProfile,
        boolean cancelled) {
    public Evidence {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(experimentId, "experimentId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(duration, "duration");
        environmentProfile = environmentProfile == null || environmentProfile.isBlank()
                ? "unknown" : environmentProfile;
    }

    public Evidence(UUID id,
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
                    Duration duration,
                    boolean timedOut,
                    boolean trusted) {
        this(id, experimentId, snapshotId, kind, command, cwd, exitCode, stdout, stderr,
                startedAt, completedAt, duration, timedOut, trusted, "unknown", false);
    }

    public static Evidence verification(UUID experimentId,
                                        UUID snapshotId,
                                        VerificationPurpose purpose,
                                        String command,
                                        String cwd,
                                        int exitCode,
                                        String stdout,
                                        String stderr,
                                        Instant startedAt,
                                        Instant completedAt,
                                        Duration duration,
                                        boolean timedOut,
                                        boolean cancelled,
                                        String environmentProfile,
                                        boolean trusted) {
        return new Evidence(UUID.randomUUID(), experimentId, snapshotId, purpose.evidenceKind(), command, cwd,
                exitCode, stdout, stderr, startedAt, completedAt, duration, timedOut, trusted,
                environmentProfile, cancelled);
    }

    public static Evidence command(UUID experimentId,
                                   UUID snapshotId,
                                   String command,
                                   String cwd,
                                   int exitCode,
                                   String stdout,
                                   String stderr,
                                   Instant startedAt,
                                   Instant completedAt,
                                   Duration duration,
                                   boolean timedOut,
                                   boolean cancelled,
                                   String environmentProfile) {
        return new Evidence(UUID.randomUUID(), experimentId, snapshotId, "AGENT_COMMAND", command, cwd,
                exitCode, stdout, stderr, startedAt, completedAt, duration, timedOut, false,
                environmentProfile, cancelled);
    }
}
