package com.offcanon.infrastructure.redis;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisSessionRunLeaseTest {
    @Test
    void assertHeldFailsWhenRedisOwnerChanges() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(values.get(anyString())).thenReturn("different-experiment");
        RedisSessionRunLease leases = new RedisSessionRunLease(redis, Duration.ofSeconds(10));
        UUID session = UUID.randomUUID();
        UUID experiment = UUID.randomUUID();

        var lease = leases.tryAcquire(session, experiment).orElseThrow();
        com.offcanon.shared.domain.DomainException error = org.junit.jupiter.api.Assertions.assertThrows(
                com.offcanon.shared.domain.DomainException.class,
                lease::assertHeld);
        org.junit.jupiter.api.Assertions.assertEquals("SESSION_RUN_LEASE_LOST", error.code());
        leases.shutdown();
    }

    @Test
    void renewsShortLeaseAndStopsAfterRelease() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true, false, true);
        AtomicInteger renewals = new AtomicInteger();
        doAnswer(invocation -> {
            renewals.incrementAndGet();
            return 1L;
        }).when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(Object[].class));

        RedisSessionRunLease leases = new RedisSessionRunLease(redis, Duration.ofMillis(1_200));
        UUID session = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        var firstLease = leases.tryAcquire(session, first).orElseThrow();
        Thread.sleep(1_700);
        assertTrue(leases.tryAcquire(session, second).isEmpty(), "active owner must keep a short lease alive");

        assertTrue(renewals.get() > 0, "active owner must renew the lease before it expires");
        firstLease.release();
        int renewalsAfterRelease = renewals.get();
        Thread.sleep(1_200);
        assertTrue(renewals.get() == renewalsAfterRelease,
                "release must stop the renewal loop");
        leases.tryAcquire(session, second).orElseThrow().release();
        leases.shutdown();
    }

    @Test
    void staleHandleCannotReleaseAReplacementLeaseForTheSameExperiment() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        AtomicReference<String> owner = new AtomicReference<>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(invocation ->
                owner.compareAndSet(null, invocation.getArgument(1)));
        when(values.get(anyString())).thenAnswer(ignored -> owner.get());
        doAnswer(invocation -> {
            Object[] arguments = invocation.getArgument(2);
            String expected = arguments[0].toString();
            if (arguments.length == 2) {
                return expected.equals(owner.get()) ? 1L : 0L;
            }
            boolean revoke = expected.endsWith("|");
            String current = owner.get();
            boolean matches = revoke
                    ? current != null && current.startsWith(expected)
                    : expected.equals(current);
            if (matches) owner.set(null);
            return matches ? 1L : 0L;
        }).when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                anyList(), any(Object[].class));

        RedisSessionRunLease leases = new RedisSessionRunLease(redis, Duration.ofSeconds(10));
        UUID session = UUID.randomUUID();
        UUID experiment = UUID.randomUUID();
        var first = leases.tryAcquire(session, experiment).orElseThrow();
        String firstToken = owner.get();
        first.release();
        // The mocked Redis script has no server-side Lua execution; reflect the
        // successful owner-token release in the backing value.
        owner.set(null);
        var replacement = leases.tryAcquire(session, experiment).orElseThrow();
        String replacementToken = owner.get();

        assertNotEquals(firstToken, replacementToken);
        first.release();
        replacement.assertHeld();
        assertEquals(replacementToken, owner.get());

        leases.revoke(session, experiment);
        assertThrows(com.offcanon.shared.domain.DomainException.class, replacement::assertHeld);
        leases.shutdown();
    }
}
