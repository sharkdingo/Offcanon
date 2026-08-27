package com.pico.infrastructure.memory;

import com.pico.port.PromotionLockPort;
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
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
