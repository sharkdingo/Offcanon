package com.offcanon.infrastructure.memory;

import com.offcanon.port.SessionRepository;
import com.offcanon.session.domain.Session;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.offcanon.shared.domain.DomainException;

@Repository
@Profile("!mysql")
public class InMemorySessionRepository implements SessionRepository {
    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();

    @Override
    public synchronized Session save(Session session) {
        Session existing = sessions.putIfAbsent(session.id(), session);
        if (existing != null && !existing.equals(session)) {
            throw new DomainException("SESSION_IDENTITY_CONFLICT",
                    "Session identity is already bound to different content: " + session.id());
        }
        return existing == null ? session : existing;
    }

    @Override
    public Optional<Session> findById(UUID id) {
        return Optional.ofNullable(sessions.get(id));
    }

    @Override
    public List<Session> findByProjectId(UUID projectId) {
        return sessions.values().stream().filter(s -> s.projectId().equals(projectId))
                .sorted(Comparator.comparing(Session::createdAt)).toList();
    }
}
