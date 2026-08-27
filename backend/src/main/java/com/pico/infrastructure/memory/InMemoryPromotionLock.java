package com.pico.infrastructure.memory;

import com.pico.port.PromotionLockPort;
import com.pico.shared.domain.DomainException;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Component
@Profile("!redis")
public class InMemoryPromotionLock implements PromotionLockPort {
    private final ConcurrentHashMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public <T> T withProjectLock(UUID projectId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(projectId, ignored -> new ReentrantLock());
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new DomainException("PROMOTION_LOCK_INTERRUPTED", "Promotion lock acquisition was interrupted");
        }
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
