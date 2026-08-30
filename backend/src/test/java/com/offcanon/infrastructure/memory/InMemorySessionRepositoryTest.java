package com.offcanon.infrastructure.memory;

import com.offcanon.session.domain.Session;
import com.offcanon.shared.domain.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemorySessionRepositoryTest {
    @Test
    void sessionIdentityIsInsertOnlyAndExactReplayIsIdempotent() {
        InMemorySessionRepository repository = new InMemorySessionRepository();
        UUID id = UUID.randomUUID();
        Session original = new Session(id, UUID.randomUUID(), "first", Instant.EPOCH, 0);

        assertSame(original, repository.save(original));
        assertSame(original, repository.save(original));

        Session conflicting = new Session(id, original.projectId(), "different", Instant.EPOCH, 0);
        DomainException error = assertThrows(DomainException.class, () -> repository.save(conflicting));
        assertEquals("SESSION_IDENTITY_CONFLICT", error.code());
        assertEquals(original, repository.findById(id).orElseThrow());
    }
}
