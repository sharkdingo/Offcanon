package com.offcanon.port;

import com.offcanon.project.domain.Project;
import com.offcanon.workspace.domain.Snapshot;

import java.nio.file.Path;

public interface SnapshotPort {
    default void validateProject(Path canonicalPath) {
        // Adapters with repository-specific constraints should reject invalid scopes here.
    }

    default Path resolveProjectRoot(Path requestedPath) {
        Path normalized = requestedPath.toAbsolutePath().normalize();
        validateProject(normalized);
        return normalized;
    }

    Snapshot capture(Project project);
    Snapshot captureWorkspace(Project project, Path workspace, String parentFingerprint);
    default void discard(Snapshot snapshot) {
        // Adapters that materialize temporary snapshots may release them here.
    }
    String currentFingerprint(Project project);
    String fingerprintWorkspace(Project project, Path workspace, String parentFingerprint);
}
