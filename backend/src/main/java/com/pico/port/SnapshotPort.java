package com.pico.port;

import com.pico.project.domain.Project;
import com.pico.workspace.domain.Snapshot;

public interface SnapshotPort {
    Snapshot capture(Project project);
    String currentFingerprint(Project project);
}
