package com.offcanon.infrastructure.memory;

import com.offcanon.identity.domain.AuthSession;
import com.offcanon.port.AuthSessionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("!mysql")
public class InMemoryAuthSessionRepository implements AuthSessionRepository {
    private final ConcurrentHashMap<String, AuthSession> sessions = new ConcurrentHashMap<>();

    @Override
    public AuthSession save(AuthSession session) {
        sessions.put(session.tokenHash(), session);
        return session;
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
