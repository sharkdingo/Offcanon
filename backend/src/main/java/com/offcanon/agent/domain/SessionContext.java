package com.offcanon.agent.domain;

import com.offcanon.memory.domain.TaskMemoryProjection;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Provenance-aware continuity that deliberately excludes prior tool observations. */
public record SessionContext(List<HistoricalTurn> turns,
                             TaskMemoryProjection memoryProjection) {

    public SessionContext {
        turns = List.copyOf(Objects.requireNonNull(turns, "turns"));
        if (turns.isEmpty()) {
            throw new IllegalArgumentException("Session context must contain at least one historical turn");
        }
    }

    public SessionContext(List<HistoricalTurn> turns) {
        this(turns, null);
    }

    public SessionContext(UUID priorExperimentId,
                          UUID priorSnapshotId,
                          String priorTask,
                          String priorSummary) {
        this(List.of(new HistoricalTurn(priorExperimentId, priorSnapshotId, priorTask,
                "UNKNOWN", priorSummary, "")), null);
    }

    public SessionContext withMemoryProjection(TaskMemoryProjection projection) {
        return new SessionContext(turns, projection);
    }

    public UUID priorExperimentId() { return turns.getLast().experimentId(); }
    public UUID priorSnapshotId() { return turns.getLast().baseSnapshotId(); }
    public String priorTask() { return turns.getLast().task(); }
    public String priorSummary() { return turns.getLast().summary(); }

    public record HistoricalTurn(
            UUID experimentId,
            UUID baseSnapshotId,
            String task,
            String status,
            String summary,
            String failureReason) {

        public HistoricalTurn {
            Objects.requireNonNull(experimentId, "experimentId");
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(status, "status");
            summary = summary == null ? "" : summary;
            failureReason = failureReason == null ? "" : failureReason;
        }
    }
}
