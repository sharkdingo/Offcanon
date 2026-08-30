package com.offcanon.infrastructure.mysql;

import com.offcanon.port.SessionRepository;
import com.offcanon.session.domain.Session;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import org.springframework.dao.DuplicateKeyException;
import com.offcanon.shared.domain.DomainException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.temporal.ChronoUnit;

@Repository
@Profile("mysql")
public class JdbcSessionRepository implements SessionRepository {
    private static final String INSERT = "INSERT INTO sessions (id,project_id,title,created_at,version) VALUES (?,?,?,?,?)";
    private final JdbcTemplate jdbc;

    public JdbcSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Session save(Session session) {
        if (session.version() != 0) {
            throw new DomainException("SESSION_IMMUTABLE", "Persisted sessions cannot be updated");
        }
        try {
            jdbc.update(INSERT, session.id().toString(), session.projectId().toString(), session.title(),
                    Timestamp.from(session.createdAt()), session.version());
            return session;
        } catch (DuplicateKeyException error) {
            Optional<Session> existing = findById(session.id());
            if (existing.isPresent() && sameSession(existing.get(), session)) {
                return existing.get();
            }
            throw new DomainException("SESSION_IDENTITY_CONFLICT",
                    "Session identity is already registered: " + session.id());
        }
    }

    /**
     * MySQL TIMESTAMP(6) persists microseconds. Compare at that precision so a
     * retry of a value created with a nanosecond-resolution clock remains
     * idempotent after the first insert has been materialized and read back.
     */
    private boolean sameSession(Session left, Session right) {
        return left.id().equals(right.id())
                && left.projectId().equals(right.projectId())
                && left.title().equals(right.title())
                && left.createdAt().truncatedTo(ChronoUnit.MICROS)
                .equals(right.createdAt().truncatedTo(ChronoUnit.MICROS))
                && left.version() == right.version();
    }

    @Override
    public Optional<Session> findById(UUID id) {
        return jdbc.query("SELECT * FROM sessions WHERE id=?", this::map, id.toString()).stream().findFirst();
    }

    @Override
    public List<Session> findByProjectId(UUID projectId) {
        return jdbc.query("SELECT * FROM sessions WHERE project_id=? ORDER BY created_at", this::map, projectId.toString());
    }

    private Session map(ResultSet rs, int row) throws SQLException {
        return new Session(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("project_id")),
                rs.getString("title"), rs.getTimestamp("created_at").toInstant(), rs.getLong("version"));
    }
}
