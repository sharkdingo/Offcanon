package com.offcanon.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persisted bearer-session metadata. The raw bearer token is never stored. */
public record AuthSession(String tokenHash,
                          UUID userId,
                          Instant createdAt,
                          Instant expiresAt) {
    public AuthSession {
        Objects.requireNonNull(tokenHash, "tokenHash");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (tokenHash.isBlank()) throw new IllegalArgumentException("Token hash must not be blank");
        if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("Session expiry must be in the future");
    }

    public boolean expiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
