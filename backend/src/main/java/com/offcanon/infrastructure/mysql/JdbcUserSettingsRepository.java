package com.offcanon.infrastructure.mysql;

import com.offcanon.identity.domain.UserSettings;
import com.offcanon.port.UserSettingsRepository;
import com.offcanon.shared.domain.DomainException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("mysql")
public class JdbcUserSettingsRepository implements UserSettingsRepository {
    private final JdbcTemplate jdbc;

    public JdbcUserSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserSettings save(UserSettings value) {
        if (value.version() == 0) {
            try {
                jdbc.update("INSERT INTO user_settings (user_id,theme,locale,model_endpoint,model_name,agent_max_steps,agent_run_timeout_seconds,context_limit_chars,updated_at,version) VALUES (?,?,?,?,?,?,?,?,?,?)",
                        value.userId().toString(), value.theme(), value.locale(), value.modelEndpoint(), value.modelName(),
                        value.agentMaxSteps(), value.agentRunTimeoutSeconds(), value.contextLimitChars(), Timestamp.from(value.updatedAt()), value.version());
            } catch (org.springframework.dao.DuplicateKeyException error) {
                throw conflict(value);
            }
        } else {
            int changed = jdbc.update("UPDATE user_settings SET theme=?,locale=?,model_endpoint=?,model_name=?,agent_max_steps=?,agent_run_timeout_seconds=?,context_limit_chars=?,updated_at=?,version=? WHERE user_id=? AND version=?",
                    value.theme(), value.locale(), value.modelEndpoint(), value.modelName(), value.agentMaxSteps(), value.agentRunTimeoutSeconds(),
                    value.contextLimitChars(), Timestamp.from(value.updatedAt()), value.version(), value.userId().toString(), value.version() - 1);
            if (changed != 1) throw conflict(value);
        }
        return value;
    }

    @Override
    public Optional<UserSettings> findByUserId(UUID userId) {
        return jdbc.query("SELECT * FROM user_settings WHERE user_id=?", this::map, userId.toString()).stream().findFirst();
    }

    private UserSettings map(ResultSet result, int row) throws SQLException {
        return new UserSettings(UUID.fromString(result.getString("user_id")), result.getString("theme"), result.getString("locale"),
                result.getString("model_endpoint"), result.getString("model_name"), result.getInt("agent_max_steps"),
                result.getLong("agent_run_timeout_seconds"), result.getInt("context_limit_chars"),
                result.getTimestamp("updated_at").toInstant(), result.getLong("version"));
    }

    private DomainException conflict(UserSettings value) {
        return new DomainException("SETTINGS_VERSION_CONFLICT", "Settings changed concurrently for user: " + value.userId());
    }
}
