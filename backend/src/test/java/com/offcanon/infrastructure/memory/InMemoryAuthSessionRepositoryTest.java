package com.offcanon.infrastructure.memory;

import com.offcanon.identity.domain.AuthSession;
import com.offcanon.shared.domain.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryAuthSessionRepositoryTest {
    @Test
    void authenticationSessionIdentityIsInsertOnly() {
        InMemoryAuthSessionRepository repository = new InMemoryAuthSessionRepository();
        Instant created = Instant.EPOCH;
        AuthSession original = new AuthSession("token-hash", java.util.UUID.randomUUID(), created, created.plusSeconds(60));

        assertSame(original, repository.save(original));
        assertSame(original, repository.save(original));

        AuthSession conflicting = new AuthSession(original.tokenHash(), java.util.UUID.randomUUID(), created, created.plusSeconds(60));
        DomainException error = assertThrows(DomainException.class, () -> repository.save(conflicting));
        assertEquals("AUTH_SESSION_IDENTITY_CONFLICT", error.code());
    }
}
