package com.offcanon.infrastructure.mysql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offcanon.identity.domain.AuthSession;
import com.offcanon.project.domain.Project;
import com.offcanon.session.domain.Session;
import com.offcanon.shared.domain.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcIdentityRepositoryTest {
    private static final Instant CREATED = Instant.parse("2026-08-29T01:02:03.123456Z");

    @Test
    void sessionSaveIsIdempotentForExactReplayAndRejectsDifferentContent() {
        UUID id = UUID.randomUUID();
        Session stored = new Session(id, UUID.randomUUID(), "first", CREATED, 0);
        DuplicateJdbcTemplate jdbc = new DuplicateJdbcTemplate(Map.of(
                "sessions WHERE id", List.of(stored)));
        JdbcSessionRepository repository = new JdbcSessionRepository(jdbc);

        Session replay = new Session(id, stored.projectId(), stored.title(),
                CREATED.plusNanos(999), stored.version());
        assertSame(stored, repository.save(replay));

        Session conflicting = new Session(id, stored.projectId(), "changed", CREATED, 0);
        DomainException error = assertThrows(DomainException.class, () -> repository.save(conflicting));
        assertEquals("SESSION_IDENTITY_CONFLICT", error.code());
    }

    @Test
    void authSessionSaveIsIdempotentAndRejectsTokenIdentityConflict() {
        String tokenHash = "token-hash";
        AuthSession stored = new AuthSession(tokenHash, UUID.randomUUID(), CREATED, CREATED.plusSeconds(60));
        DuplicateJdbcTemplate jdbc = new DuplicateJdbcTemplate(Map.of(
                "auth_sessions WHERE token_hash", List.of(stored)));
        JdbcAuthSessionRepository repository = new JdbcAuthSessionRepository(jdbc);

        AuthSession replay = new AuthSession(tokenHash, stored.userId(),
                CREATED.plusNanos(999), CREATED.plusSeconds(60).plusNanos(999));
        assertSame(stored, repository.save(replay));

        AuthSession conflicting = new AuthSession(tokenHash, UUID.randomUUID(), CREATED, CREATED.plusSeconds(60));
        DomainException error = assertThrows(DomainException.class, () -> repository.save(conflicting));
        assertEquals("AUTH_SESSION_IDENTITY_CONFLICT", error.code());
    }

    @Test
    void projectSaveDistinguishesIdempotentReplayIdentityConflictAndCanonicalDuplicate() {
        UUID owner = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Project stored = new Project(id, "demo", Path.of("D:/projects/demo"), List.of("mvn test"), CREATED, 0, owner);
        DuplicateJdbcTemplate replayJdbc = new DuplicateJdbcTemplate(Map.of(
                "projects WHERE id", List.of(stored)));
        JdbcProjectRepository replayRepository = new JdbcProjectRepository(replayJdbc, new ObjectMapper());

        Project replay = new Project(id, stored.name(), Path.of("D:/projects/demo"), stored.verificationCommands(),
                CREATED.plusNanos(999), 0, owner);
        assertSame(stored, replayRepository.save(replay));

        Project conflicting = new Project(id, "changed", stored.canonicalPath(), stored.verificationCommands(), CREATED, 0, owner);
        DomainException identityError = assertThrows(DomainException.class, () -> replayRepository.save(conflicting));
        assertEquals("PROJECT_IDENTITY_CONFLICT", identityError.code());

        UUID otherId = UUID.randomUUID();
        Project canonicalOwner = new Project(otherId, "existing", stored.canonicalPath(), List.of(), CREATED, 0, owner);
        DuplicateJdbcTemplate canonicalJdbc = new DuplicateJdbcTemplate(Map.of(
                "projects WHERE id", List.of(),
                "projects WHERE canonical_path_key", List.of(canonicalOwner)));
        JdbcProjectRepository canonicalRepository = new JdbcProjectRepository(canonicalJdbc, new ObjectMapper());
        Project duplicatePath = new Project(UUID.randomUUID(), "new", stored.canonicalPath(), List.of(), CREATED, 0, owner);
        DomainException pathError = assertThrows(DomainException.class, () -> canonicalRepository.save(duplicatePath));
        assertEquals("PROJECT_ALREADY_REGISTERED", pathError.code());

        DuplicateJdbcTemplate sameIdByPathJdbc = new DuplicateJdbcTemplate(Map.of(
                "projects WHERE id", List.of(),
                "projects WHERE canonical_path_key", List.of(stored)));
        JdbcProjectRepository sameIdByPathRepository = new JdbcProjectRepository(sameIdByPathJdbc, new ObjectMapper());
        assertSame(stored, sameIdByPathRepository.save(replay));
    }

    /** JdbcTemplate test double that models a duplicate insert and keyed reads. */
    private static final class DuplicateJdbcTemplate extends JdbcTemplate {
        private final Map<String, List<?>> queryResults;

        private DuplicateJdbcTemplate(Map<String, List<?>> queryResults) {
            this.queryResults = queryResults;
        }

        @Override
        public int update(String sql, Object... args) {
            throw new DuplicateKeyException("duplicate");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            for (Map.Entry<String, List<?>> entry : queryResults.entrySet()) {
                if (sql.contains(entry.getKey())) {
                    return (List<T>) entry.getValue();
                }
            }
            return List.of();
        }
    }
}
