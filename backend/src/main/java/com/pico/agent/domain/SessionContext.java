package com.pico.agent.domain;

import java.util.Objects;
import java.util.UUID;

/** Provenance-aware continuity that deliberately excludes prior tool observations. */
public record SessionContext(
        UUID priorExperimentId,
        UUID priorSnapshotId,
        String priorTask,
        String priorSummary) {

    public SessionContext {
        Objects.requireNonNull(priorExperimentId, "priorExperimentId");
        Objects.requireNonNull(priorSnapshotId, "priorSnapshotId");
        Objects.requireNonNull(priorTask, "priorTask");
        Objects.requireNonNull(priorSummary, "priorSummary");
    }
}
