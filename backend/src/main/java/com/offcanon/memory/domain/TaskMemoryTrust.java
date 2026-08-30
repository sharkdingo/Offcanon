package com.offcanon.memory.domain;

/** How strongly a memory revision may be relied upon. */
public enum TaskMemoryTrust {
    AGENT_REPORTED,
    USER_CONFIRMED,
    VERIFIED,
    PROMOTED,
    SYSTEM;

    public boolean isTrustedFactSource() {
        return this == VERIFIED || this == PROMOTED || this == SYSTEM;
    }
}
