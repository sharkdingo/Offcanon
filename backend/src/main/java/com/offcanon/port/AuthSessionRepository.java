package com.offcanon.port;

import com.offcanon.identity.domain.AuthSession;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthSessionRepository {
    AuthSession save(AuthSession session);
    Optional<AuthSession> findByTokenHash(String tokenHash);
    void deleteByTokenHash(String tokenHash);
    void deleteExpired(Instant now);
    void deleteByUserId(UUID userId);
}
