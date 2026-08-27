package com.offcanon.port;

import com.offcanon.session.domain.Session;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository {
    Session save(Session session);
    Optional<Session> findById(UUID id);
    List<Session> findByProjectId(UUID projectId);
}
