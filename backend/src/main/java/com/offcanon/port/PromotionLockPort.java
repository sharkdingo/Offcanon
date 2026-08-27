package com.offcanon.port;

import java.util.UUID;
import java.util.function.Supplier;

public interface PromotionLockPort {
    <T> T withProjectLock(UUID projectId, Supplier<T> action);
}
