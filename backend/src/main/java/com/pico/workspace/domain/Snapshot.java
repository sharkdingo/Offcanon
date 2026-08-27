package com.pico.workspace.domain;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Snapshot(
        UUID id,
        UUID projectId,
        String fingerprint,
        Path materializedPath,
        Instant capturedAt,
        List<String> includedFiles,
        List<ExcludedPath> excludedFiles) {

    public Snapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(materializedPath, "materializedPath");
        Objects.requireNonNull(capturedAt, "capturedAt");
        includedFiles = List.copyOf(includedFiles);
        excludedFiles = List.copyOf(excludedFiles);
    }

    public record ExcludedPath(String path, String reason) {
        public ExcludedPath {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
