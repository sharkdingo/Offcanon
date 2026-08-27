package com.offcanon.infrastructure.mysql;

import com.offcanon.identity.domain.AuthSession;
import com.offcanon.port.AuthSessionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
@Profile("mysql")
public class JdbcAuthSessionRepository implements AuthSessionRepository {
    private final JdbcTemplate jdbc;

    public JdbcAuthSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AuthSession save(AuthSession session) {
        jdbc.update("INSERT INTO auth_sessions (token_hash,user_id,created_at,expires_at) VALUES (?,?,?,?)",
                session.tokenHash(), session.userId().toString(), Timestamp.from(session.createdAt()), Timestamp.from(session.expiresAt()));
        return session;
    }

    @Override
    public Optional<AuthSession> findByTokenHash(String tokenHash) {
        return jdbc.query("SELECT * FROM auth_sessions WHERE token_hash=?", this::map, tokenHash).stream().findFirst();
    }

    @Override
    public void deleteByTokenHash(String tokenHash) {
        jdbc.update("DELETE FROM auth_sessions WHERE token_hash=?", tokenHash);
    }

    @Override
    public void deleteExpired(Instant now) {
        jdbc.update("DELETE FROM auth_sessions WHERE expires_at<=?", Timestamp.from(now));
    }

    private AuthSession map(ResultSet result, int row) throws SQLException {
        return new AuthSession(result.getString("token_hash"), java.util.UUID.fromString(result.getString("user_id")),
                result.getTimestamp("created_at").toInstant(), result.getTimestamp("expires_at").toInstant());
    }
}
