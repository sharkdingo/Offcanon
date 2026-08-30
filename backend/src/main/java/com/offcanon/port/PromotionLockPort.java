package com.offcanon.port;

import java.util.UUID;
import java.util.function.Supplier;

public interface PromotionLockPort {
    <T> T withProjectLock(UUID projectId, Supplier<T> action);

    /**
     * Verifies that the current thread still owns the project lock. Every
     * implementation must fail closed when ownership is absent or lost;
     * distributed adapters additionally validate their lease token.
     */
    void assertHeld(UUID projectId);
}
