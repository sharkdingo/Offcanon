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
        boolean trusted) {
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
    }

    public static Evidence verification(UUID experimentId,
                                        UUID snapshotId,
                                        String command,
                                        String cwd,
                                        int exitCode,
                                        String stdout,
                                        String stderr,
                                        Instant startedAt,
                                        Instant completedAt,
                                        Duration duration,
                                        boolean timedOut) {
        return new Evidence(UUID.randomUUID(), experimentId, snapshotId, "VERIFICATION", command, cwd,
                exitCode, stdout, stderr, startedAt, completedAt, duration, timedOut, true);
    }
}
