package com.offcanon.infrastructure.memory;

import com.offcanon.port.SessionRunLeasePort;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemorySessionRunLeaseTest {
    @Test
    void onlyOneExperimentCanOwnASessionLease() {
        InMemorySessionRunLease leases = new InMemorySessionRunLease();
        UUID session = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        SessionRunLeasePort.Lease firstLease = leases.tryAcquire(session, first).orElseThrow();
        assertTrue(leases.tryAcquire(session, first).isEmpty());
        assertTrue(leases.tryAcquire(session, second).isEmpty());
        firstLease.release();
        SessionRunLeasePort.Lease secondLease = leases.tryAcquire(session, second).orElseThrow();
        secondLease.assertHeld();
        assertThrows(com.offcanon.shared.domain.DomainException.class,
                firstLease::assertHeld);

        firstLease.release();
        secondLease.assertHeld();
        leases.revoke(session, first);
        secondLease.assertHeld();
        leases.revoke(session, second);
        assertThrows(com.offcanon.shared.domain.DomainException.class, secondLease::assertHeld);
    }
}
