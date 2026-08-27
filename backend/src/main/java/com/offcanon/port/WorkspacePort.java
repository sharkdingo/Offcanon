package com.offcanon.port;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.workspace.domain.Snapshot;

import java.nio.file.Path;
import java.util.UUID;

public interface WorkspacePort {
    Path materialize(Snapshot snapshot, UUID experimentId);
    Path createVerificationWorkspace(Snapshot result, Experiment experiment);
    Path createPromotionCandidate(Snapshot result, Experiment experiment);
}
