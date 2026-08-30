package com.offcanon.infrastructure.sqlite;

import com.offcanon.port.PromotionLockPort;
import com.offcanon.shared.domain.DomainException;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Process-local project lock; the application instance lock prevents duplicates. */
@Component
public final class LocalPromotionLock implements PromotionLockPort {
    private final ConcurrentHashMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public <T> T withProjectLock(UUID projectId, Supplier<T> action) {
        if (projectId == null) throw new IllegalArgumentException("projectId must not be null");
        ReentrantLock lock = locks.computeIfAbsent(projectId, ignored -> new ReentrantLock());
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new DomainException("PROMOTION_LOCK_INTERRUPTED", "Promotion lock acquisition was interrupted");
        }
        try { return action.get(); } finally { lock.unlock(); }
    }

    @Override
    public void assertHeld(UUID projectId) {
        ReentrantLock lock = projectId == null ? null : locks.get(projectId);
        if (lock == null || !lock.isHeldByCurrentThread()) {
            throw new DomainException("PROMOTION_LOCK_LOST", "The current thread does not hold the project promotion lock");
        }
    }
}
