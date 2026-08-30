package com.offcanon.infrastructure.sqlite;

import com.offcanon.identity.domain.AuthSession;
import com.offcanon.port.AuthSessionRepository;
import com.offcanon.shared.domain.DomainException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Repository

public class SqliteAuthSessionRepository implements AuthSessionRepository {
    private final JdbcTemplate jdbc;

    public SqliteAuthSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AuthSession save(AuthSession session) {
        try {
            jdbc.update("INSERT INTO auth_sessions (token_hash,user_id,created_at,expires_at) VALUES (?,?,?,?)",
                    session.tokenHash(), session.userId().toString(), SqliteValues.epochMicros(session.createdAt()),
                    SqliteValues.epochMicros(session.expiresAt()));
            return session;
        } catch (DuplicateKeyException error) {
            Optional<AuthSession> existing = findByTokenHash(session.tokenHash());
            if (existing.isPresent() && sameSession(existing.get(), session)) {
                return existing.get();
            }
            throw new DomainException("AUTH_SESSION_IDENTITY_CONFLICT",
                    "Authentication session identity is already bound to different content");
        }
    }

    private boolean sameSession(AuthSession left, AuthSession right) {
        return left.tokenHash().equals(right.tokenHash())
                && left.userId().equals(right.userId())
                && left.createdAt().truncatedTo(ChronoUnit.MICROS)
                .equals(right.createdAt().truncatedTo(ChronoUnit.MICROS))
                && left.expiresAt().truncatedTo(ChronoUnit.MICROS)
                .equals(right.expiresAt().truncatedTo(ChronoUnit.MICROS));
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
        jdbc.update("DELETE FROM auth_sessions WHERE expires_at<=?", SqliteValues.epochMicros(now));
    }

    @Override
    public void deleteByUserId(UUID userId) {
        jdbc.update("DELETE FROM auth_sessions WHERE user_id=?", userId.toString());
    }

    private AuthSession map(ResultSet result, int row) throws SQLException {
        return new AuthSession(result.getString("token_hash"), java.util.UUID.fromString(result.getString("user_id")),
                SqliteValues.instant(result, "created_at"), SqliteValues.instant(result, "expires_at"));
    }
}
