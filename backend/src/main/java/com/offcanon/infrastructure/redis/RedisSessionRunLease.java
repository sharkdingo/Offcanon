package com.offcanon.infrastructure.redis;

import com.offcanon.port.SessionRunLeasePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Profile("redis")
public class RedisSessionRunLease implements SessionRunLeasePort {
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> REVOKE = new DefaultRedisScript<>(
            "local value=redis.call('get', KEYS[1]); "
                    + "if value and string.sub(value,1,string.len(ARGV[1])) == ARGV[1] "
                    + "then return redis.call('del', KEYS[1]) else return 0 end", Long.class);
    private final StringRedisTemplate redis;
    private final Duration lease;
    private final Set<RedisLease> activeLeases = ConcurrentHashMap.newKeySet();

    @org.springframework.beans.factory.annotation.Autowired
    public RedisSessionRunLease(StringRedisTemplate redis,
                                @Value("${offcanon.redis.session-lease-seconds:${OFFCANON_REDIS_SESSION_LEASE_SECONDS:1800}}") long leaseSeconds,
                                @Value("${offcanon.agent.run-timeout-seconds:600}") long runTimeoutSeconds) {
        this.redis = redis;
        // The initial lease keeps a wide margin while the owner renews it for the
        // lifetime of the run. Configuration cannot accidentally make it shorter
        // than the global run timeout plus a recovery margin.
        this.lease = Duration.ofSeconds(Math.max(Math.max(60, leaseSeconds), runTimeoutSeconds + 60));
    }

    /**
     * Constructor used by focused adapter tests. Production wiring goes through
     * the value-based constructor above so the minimum lease policy remains
     * explicit at the configuration boundary.
     */
    RedisSessionRunLease(StringRedisTemplate redis, Duration lease) {
        this.redis = redis;
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("Session lease must be positive");
        }
        this.lease = lease;
    }

    @Override
    public Optional<Lease> tryAcquire(UUID sessionId, UUID experimentId) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(experimentId, "experimentId");
        String key = key(sessionId);
        String token = experimentPrefix(experimentId) + UUID.randomUUID();
        Boolean acquired = redis.opsForValue().setIfAbsent(key, token, lease);
        if (!Boolean.TRUE.equals(acquired)) return Optional.empty();

        RedisLease acquiredLease = new RedisLease(sessionId, experimentId, key, token);
        activeLeases.add(acquiredLease);
        try {
            acquiredLease.startRenewal();
            return Optional.of(acquiredLease);
        } catch (RuntimeException | Error startupFailure) {
            acquiredLease.release();
            throw startupFailure;
        }
    }

    @Override
    public void revoke(UUID sessionId, UUID experimentId) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(experimentId, "experimentId");
        activeLeases.stream()
                .filter(current -> current.sessionId.equals(sessionId)
                        && current.experimentId.equals(experimentId))
                .forEach(RedisLease::markLost);
        try {
            redis.execute(REVOKE, List.of(key(sessionId)), experimentPrefix(experimentId));
        } catch (RuntimeException ignored) {
            // Durable cancellation remains authoritative. Stopping renewal makes
            // the remote lease expire even when Redis is temporarily unavailable.
        }
    }

    @PreDestroy
    void shutdown() {
        activeLeases.forEach(RedisLease::stopForShutdown);
        activeLeases.clear();
    }

    private final class RedisLease implements Lease {
        private final UUID sessionId;
        private final UUID experimentId;
        private final String key;
        private final String token;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private volatile Thread renewalThread;

        private RedisLease(UUID sessionId, UUID experimentId, String key, String token) {
            this.sessionId = sessionId;
            this.experimentId = experimentId;
            this.key = key;
            this.token = token;
        }

        @Override
        public UUID sessionId() {
            return sessionId;
        }

        @Override
        public UUID experimentId() {
            return experimentId;
        }

        @Override
        public void assertHeld() {
            if (!active.get() || Thread.currentThread().isInterrupted()) {
                throw leaseLost("Session run lease was lost");
            }
            final String owner;
            try {
                owner = redis.opsForValue().get(key);
            } catch (RuntimeException verificationFailure) {
                markLost();
                throw leaseLost("Session run lease could not be verified");
            }
            if (!token.equals(owner)) {
                markLost();
                throw leaseLost("Session run lease was lost");
            }
        }

        @Override
        public void release() {
            if (!active.compareAndSet(true, false)) return;
            stopRenewal();
            activeLeases.remove(this);
            try {
                redis.execute(RELEASE, List.of(key), token);
            } catch (RuntimeException ignored) {
                // The owner token prevents a stale release from deleting a newer
                // lease. If Redis is unavailable, this exact lease may expire.
            }
        }

        private void startRenewal() {
            renewalThread = Thread.ofVirtual()
                    .name("offcanon-session-lease-renewal-" + sessionId)
                    .start(this::renewUntilStopped);
        }

        private void renewUntilStopped() {
            long intervalMillis = Math.max(50L, lease.toMillis() / 3);
            while (active.get()) {
                try {
                    Thread.sleep(intervalMillis);
                    if (!active.get()) return;
                    Long renewed = redis.execute(RENEW, List.of(key), token, Long.toString(lease.toMillis()));
                    if (renewed == null || renewed != 1L) {
                        markLost();
                        return;
                    }
                } catch (InterruptedException stopped) {
                    return;
                } catch (RuntimeException renewalFailure) {
                    markLost();
                    return;
                }
            }
        }

        private void markLost() {
            if (active.compareAndSet(true, false)) {
                stopRenewal();
                activeLeases.remove(this);
            }
        }

        private void stopForShutdown() {
            active.set(false);
            stopRenewal();
        }

        private void stopRenewal() {
            Thread current = renewalThread;
            if (current != null && current != Thread.currentThread()) current.interrupt();
        }

        private com.offcanon.shared.domain.DomainException leaseLost(String message) {
            return new com.offcanon.shared.domain.DomainException("SESSION_RUN_LEASE_LOST", message);
        }
    }

    private String key(UUID sessionId) {
        return "offcanon:session-run:" + sessionId;
    }

    private String experimentPrefix(UUID experimentId) {
        return experimentId + "|";
    }
}
