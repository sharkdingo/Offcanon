package com.offcanon.memory.domain;

/** Lifecycle of an append-only memory revision. */
public enum TaskMemoryStatus {
    PROPOSED,
    ACCEPTED,
    STALE,
    SUPERSEDED,
    CONFLICTED,
    REJECTED;

    public boolean isVisible() {
        return this == PROPOSED || this == ACCEPTED || this == STALE || this == CONFLICTED;
    }
}
