package com.offcanon.infrastructure.redis;

import com.offcanon.shared.domain.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisPromotionLockTest {
    @Test
    void assertHeldChecksTheOwnerTokenForTheCurrentProject() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any())).thenAnswer(invocation -> {
            currentToken.set(invocation.getArgument(1, String.class));
            return true;
        });
        when(values.get(anyString())).thenAnswer(invocation -> invocation.getArgument(0, String.class).contains("promotion-lock")
                ? currentToken.get() : null);
        RedisPromotionLock lock = new RedisPromotionLock(redis, 1, 30);
        UUID project = UUID.randomUUID();

        Boolean[] invoked = {false};
        // The token is supplied to the mocked Redis operation; expose it to the
        // get() stub so the lock can validate ownership.
        try {
            String result = lock.withProjectLock(project, () -> {
                lock.assertHeld(project);
                invoked[0] = true;
                return "ok";
            });

            assertTrue(invoked[0]);
            assertEquals("ok", result);
        } finally {
            currentToken.remove();
        }
    }

    private static final ThreadLocal<String> currentToken = new ThreadLocal<>();

    @Test
    void mismatchedTokenFailsClosed() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(values.get(anyString())).thenReturn("another-owner");
        RedisPromotionLock lock = new RedisPromotionLock(redis, 1, 30);
        UUID project = UUID.randomUUID();

        DomainException error = assertThrows(DomainException.class,
                () -> lock.withProjectLock(project, () -> {
                    lock.assertHeld(project);
                    return null;
                }));
        assertEquals("PROMOTION_LOCK_LOST", error.code());
    }
}
