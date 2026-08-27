package com.offcanon.infrastructure.mysql;

import com.offcanon.identity.domain.User;
import com.offcanon.port.UserRepository;
import com.offcanon.shared.domain.DomainException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("mysql")
public class JdbcUserRepository implements UserRepository {
    private final JdbcTemplate jdbc;

    public JdbcUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public User save(User user) {
        try {
            if (user.version() == 0) {
                jdbc.update("INSERT INTO users (id,username,password_hash,created_at,version) VALUES (?,?,?,?,?)",
                        user.id().toString(), user.username(), user.passwordHash(), Timestamp.from(user.createdAt()), user.version());
            } else {
                int changed = jdbc.update("UPDATE users SET username=?, password_hash=?, version=? WHERE id=? AND version=?",
                        user.username(), user.passwordHash(), user.version(), user.id().toString(), user.version() - 1);
                if (changed != 1) throw conflict(user);
            }
            return user;
        } catch (DuplicateKeyException error) {
            throw new DomainException("USERNAME_TAKEN", "Username is already registered");
        }
    }

    @Override
    public Optional<User> findById(UUID id) {
        return jdbc.query("SELECT * FROM users WHERE id=?", this::map, id.toString()).stream().findFirst();
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return jdbc.query("SELECT * FROM users WHERE username=?", this::map,
                User.normalizeUsername(username)).stream().findFirst();
    }

    @Override
    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        return count == null ? 0 : count;
    }

    private User map(ResultSet result, int row) throws SQLException {
        return new User(UUID.fromString(result.getString("id")), result.getString("username"),
                result.getString("password_hash"), result.getTimestamp("created_at").toInstant(), result.getLong("version"));
    }

    private DomainException conflict(User user) {
        return new DomainException("USER_VERSION_CONFLICT", "User changed concurrently: " + user.id());
    }
}
