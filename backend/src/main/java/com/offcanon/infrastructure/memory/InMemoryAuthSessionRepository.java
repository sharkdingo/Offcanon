package com.offcanon.infrastructure.memory;

import com.offcanon.identity.domain.AuthSession;
import com.offcanon.port.AuthSessionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import com.offcanon.shared.domain.DomainException;

@Repository
@Profile("!mysql")
public class InMemoryAuthSessionRepository implements AuthSessionRepository {
    private final ConcurrentHashMap<String, AuthSession> sessions = new ConcurrentHashMap<>();

    @Override
    public AuthSession save(AuthSession session) {
        AuthSession existing = sessions.putIfAbsent(session.tokenHash(), session);
        if (existing != null && !existing.equals(session)) {
            throw new DomainException("AUTH_SESSION_IDENTITY_CONFLICT",
                    "Authentication session identity is already bound to different content");
        }
        return existing == null ? session : existing;
    }

    @Override
    public Optional<AuthSession> findByTokenHash(String tokenHash) {
        return Optional.ofNullable(sessions.get(tokenHash));
    }

    @Override
    public void deleteByTokenHash(String tokenHash) {
        sessions.remove(tokenHash);
    }

    @Override
    public void deleteExpired(Instant now) {
        sessions.entrySet().removeIf(entry -> entry.getValue().expiredAt(now));
    }
}
