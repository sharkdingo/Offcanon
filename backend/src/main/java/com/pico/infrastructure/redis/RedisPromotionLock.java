package com.pico.infrastructure.redis;

import com.pico.port.PromotionLockPort;
import com.pico.shared.domain.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Component
@Profile("redis")
public class RedisPromotionLock implements PromotionLockPort {
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);
    private final StringRedisTemplate redis;
    private final Duration waitTimeout;
    private final Duration lease;

    public RedisPromotionLock(StringRedisTemplate redis,
                              @Value("${pico.redis.lock-wait-seconds:${PICO_REDIS_LOCK_WAIT_SECONDS:10}}") long waitSeconds,
                              @Value("${pico.redis.lock-lease-seconds:${PICO_REDIS_LOCK_LEASE_SECONDS:60}}") long leaseSeconds) {
        this.redis = redis;
        this.waitTimeout = Duration.ofSeconds(Math.max(1, waitSeconds));
        this.lease = Duration.ofSeconds(Math.max(5, leaseSeconds));
    }

    @Override
    public <T> T withProjectLock(UUID projectId, Supplier<T> action) {
        String key = "pico:promotion-lock:" + projectId;
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + waitTimeout.toNanos();
        boolean acquired = false;
        while (!acquired && System.nanoTime() < deadline) {
            if (Thread.currentThread().isInterrupted()) {
                throw new DomainException("PROMOTION_LOCK_INTERRUPTED", "Promotion lock acquisition was interrupted");
            }
            Boolean result = redis.opsForValue().setIfAbsent(key, token, lease);
            acquired = Boolean.TRUE.equals(result);
            if (!acquired) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new DomainException("PROMOTION_LOCK_INTERRUPTED", "Promotion lock acquisition was interrupted");
                }
            }
        }
        if (!acquired) {
            throw new DomainException("PROMOTION_LOCK_TIMEOUT", "Another promotion is currently in progress");
        }
        try {
            return action.get();
        } finally {
            redis.execute(RELEASE, List.of(key), token);
        }
    }
}
