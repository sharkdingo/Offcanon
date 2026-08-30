package com.offcanon.memory.domain;

/** Typed slots in the durable task-memory ledger. */
public enum TaskMemoryKind {
    GOAL,
    CONSTRAINT,
    DECISION,
    COMPLETED,
    IN_PROGRESS,
    OPEN_RISK,
    NEXT_ACTION,
    HYPOTHESIS,
    VERIFIED_FACT
}
