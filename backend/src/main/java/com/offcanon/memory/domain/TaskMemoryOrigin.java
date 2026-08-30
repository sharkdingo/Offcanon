package com.offcanon.memory.domain;

/** The actor that authored a memory revision. */
public enum TaskMemoryOrigin {
    USER_AUTHORED,
    AGENT_REPORTED,
    VERIFIED_SYSTEM
}
