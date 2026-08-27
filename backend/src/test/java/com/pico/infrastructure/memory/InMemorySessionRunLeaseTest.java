package com.pico.infrastructure.memory;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemorySessionRunLeaseTest {
    @Test
    void onlyOneExperimentCanOwnASessionLease() {
        InMemorySessionRunLease leases = new InMemorySessionRunLease();
        UUID session = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(leases.tryAcquire(session, first));
        assertFalse(leases.tryAcquire(session, first));
        assertFalse(leases.tryAcquire(session, second));
        leases.release(session, first);
        assertTrue(leases.tryAcquire(session, second));
    }
}
