package com.pico.experiment.domain;

public enum ExperimentStatus {
    CREATED,
    SNAPSHOTTING,
    READY_TO_RUN,
    RUNNING,
    AGENT_COMPLETED,
    VERIFYING,
    VERIFIED,
    REJECTED,
    STALE,
    PREPARING_PROMOTION,
    PROMOTING,
    PROMOTED,
    RECOVERY_REQUIRED,
    FAILED,
    CANCELLED
}
