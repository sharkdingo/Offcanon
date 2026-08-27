package com.pico.infrastructure.mysql;

import com.pico.port.SessionRepository;
import com.pico.session.domain.Session;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("mysql")
public class JdbcSessionRepository implements SessionRepository {
    private static final String UPSERT = "INSERT INTO sessions (id,project_id,title,created_at,version) VALUES (?,?,?,?,?) "
            + "ON DUPLICATE KEY UPDATE title=VALUES(title), version=VALUES(version)";
    private final JdbcTemplate jdbc;

    public JdbcSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Session save(Session session) {
        jdbc.update(UPSERT, session.id().toString(), session.projectId().toString(), session.title(),
                Timestamp.from(session.createdAt()), session.version());
        return session;
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
