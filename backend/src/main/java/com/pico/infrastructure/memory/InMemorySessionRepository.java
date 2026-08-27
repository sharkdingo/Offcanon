package com.pico.infrastructure.memory;

import com.pico.port.SessionRepository;
import com.pico.session.domain.Session;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("!mysql")
public class InMemorySessionRepository implements SessionRepository {
    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();

    @Override
    public Session save(Session session) {
        sessions.put(session.id(), session);
        return session;
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
