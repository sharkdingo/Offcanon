package com.pico.infrastructure.redis;

import com.pico.port.SessionRunLeasePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
@Profile("redis")
public class RedisSessionRunLease implements SessionRunLeasePort {
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);
    private final StringRedisTemplate redis;
    private final Duration lease;

    public RedisSessionRunLease(StringRedisTemplate redis,
                                @Value("${pico.redis.session-lease-seconds:${PICO_REDIS_SESSION_LEASE_SECONDS:1800}}") long leaseSeconds,
                                @Value("${pico.agent.run-timeout-seconds:600}") long runTimeoutSeconds) {
        this.redis = redis;
        // A run cannot legitimately outlive this lease; the default keeps a wide
        // margin while configuration cannot accidentally make the lease shorter.
        this.lease = Duration.ofSeconds(Math.max(Math.max(60, leaseSeconds), runTimeoutSeconds + 60));
    }

    @Override
    public boolean tryAcquire(UUID sessionId, UUID experimentId) {
        String key = key(sessionId);
        String owner = experimentId.toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(key, owner, lease);
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public void release(UUID sessionId, UUID experimentId) {
        redis.execute(RELEASE, List.of(key(sessionId)), experimentId.toString());
    }

    private String key(UUID sessionId) {
        return "pico:session-run:" + sessionId;
    }
}
