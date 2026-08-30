package com.offcanon.infrastructure.redis;

import com.offcanon.port.PromotionLockPort;
import com.offcanon.shared.domain.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Profile("redis")
public class RedisPromotionLock implements PromotionLockPort {
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end", Long.class);
    private final StringRedisTemplate redis;
    private final Duration waitTimeout;
    private final Duration lease;
    private final ThreadLocal<LockContext> contexts = new ThreadLocal<>();

    public RedisPromotionLock(StringRedisTemplate redis,
                              @Value("${offcanon.redis.lock-wait-seconds:${OFFCANON_REDIS_LOCK_WAIT_SECONDS:10}}") long waitSeconds,
                              @Value("${offcanon.redis.lock-lease-seconds:${OFFCANON_REDIS_LOCK_LEASE_SECONDS:60}}") long leaseSeconds) {
        this.redis = redis;
        this.waitTimeout = Duration.ofSeconds(Math.max(1, waitSeconds));
        this.lease = Duration.ofSeconds(Math.max(5, leaseSeconds));
    }

    @Override
    public <T> T withProjectLock(UUID projectId, Supplier<T> action) {
        String key = "offcanon:promotion-lock:" + projectId;
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
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean lost = new AtomicBoolean();
        Thread owner = Thread.currentThread();
        LockContext context = new LockContext(projectId, key, token, lost);
        LockContext previous = contexts.get();
        contexts.set(context);
        Thread renewer = Thread.ofVirtual().name("offcanon-promotion-lock-renewal").start(() -> {
            long intervalMillis = Math.max(1_000L, lease.toMillis() / 3);
            while (!finished.get()) {
                try {
                    Thread.sleep(intervalMillis);
                    if (finished.get()) return;
                    Long renewed = redis.execute(RENEW, List.of(key), token, Long.toString(lease.toMillis()));
                    if (renewed == null || renewed != 1L) {
                        lost.set(true);
                        owner.interrupt();
                        return;
                    }
                } catch (InterruptedException stopped) {
                    return;
                } catch (RuntimeException renewalFailure) {
                    lost.set(true);
                    owner.interrupt();
                    return;
                }
            }
        });
        try {
            // The action is responsible for checking the lease at every
            // canonical-write boundary. Once it returns, its durable result
            // (including a committed lifecycle transition) is authoritative;
            // a renewal failure observed during the final cleanup window must
            // not overwrite that result with a second, contradictory failure.
            return action.get();
        } finally {
            finished.set(true);
            renewer.interrupt();
            if (lost.get()) Thread.interrupted();
            if (previous == null) contexts.remove();
            else contexts.set(previous);
            try {
                redis.execute(RELEASE, List.of(key), token);
            } catch (RuntimeException ignored) {
                // The action result is authoritative. A release outage must not turn a
                // committed promotion into an API-level failure; the lease will expire.
            }
        }
    }

    @Override
    public void assertHeld(UUID projectId) {
        LockContext context = contexts.get();
        if (context == null || !context.projectId.equals(projectId) || context.lost.get()
                || Thread.currentThread().isInterrupted()) {
            Thread.interrupted();
            throw lockLost();
        }
        String owner;
        try {
            owner = redis.opsForValue().get(context.key);
        } catch (RuntimeException error) {
            context.lost.set(true);
            Thread.interrupted();
            throw lockLost();
        }
        if (!context.token.equals(owner)) {
            context.lost.set(true);
            Thread.interrupted();
            throw lockLost();
        }
    }

    private DomainException lockLost() {
        return new DomainException("PROMOTION_LOCK_LOST", "Promotion lock lease was lost during canonical apply");
    }

    private static final class LockContext {
        private final UUID projectId;
        private final String key;
        private final String token;
        private final AtomicBoolean lost;

        private LockContext(UUID projectId, String key, String token, AtomicBoolean lost) {
            this.projectId = projectId;
            this.key = key;
            this.token = token;
            this.lost = lost;
        }
    }
}
