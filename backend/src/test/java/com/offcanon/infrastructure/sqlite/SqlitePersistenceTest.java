package com.offcanon.infrastructure.sqlite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offcanon.agent.domain.RunEvent;
import com.offcanon.identity.domain.UserSettings;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlitePersistenceTest {
    @TempDir
    Path dataRoot;

    @Test
    void storesMicrosecondInstantsAndEnablesDurableConnectionPragmas() throws Exception {
        try (ApplicationInstanceLock lock = new ApplicationInstanceLock(dataRoot.toString());
             HikariDataSource dataSource = (HikariDataSource) new SqlitePersistenceConfiguration()
                     .dataSource(dataRoot.toString(), lock)) {
            JdbcTemplate jdbc = new SqlitePersistenceConfiguration().jdbcTemplate(dataSource);
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

            assertEquals(1, jdbc.queryForObject("PRAGMA foreign_keys", Integer.class));
            assertEquals(10_000, jdbc.queryForObject("PRAGMA busy_timeout", Integer.class));
            assertEquals("wal", jdbc.queryForObject("PRAGMA journal_mode", String.class));

            Instant value = Instant.parse("2026-08-29T01:02:03.123456Z");
            jdbc.update("INSERT INTO users (id,username,password_hash,created_at,version) VALUES (?,?,?,?,?)",
                    UUID.randomUUID().toString(), "micro-user", "hash", SqliteValues.epochMicros(value), 0);
            Long raw = jdbc.queryForObject("SELECT created_at FROM users WHERE username=?", Long.class, "micro-user");
            assertEquals(SqliteValues.epochMicros(value), raw);
            assertEquals(value, SqliteValues.instant(raw));
        }
    }

    @Test
    void translatesSqliteUniqueConstraintsToDuplicateKeyException() throws Exception {
        try (ApplicationInstanceLock lock = new ApplicationInstanceLock(dataRoot.toString());
             HikariDataSource dataSource = (HikariDataSource) new SqlitePersistenceConfiguration()
                     .dataSource(dataRoot.toString(), lock)) {
            JdbcTemplate jdbc = new SqlitePersistenceConfiguration().jdbcTemplate(dataSource);
            jdbc.execute("CREATE TABLE duplicate_probe (value TEXT UNIQUE NOT NULL)");
            jdbc.update("INSERT INTO duplicate_probe(value) VALUES (?)", "same");
            assertThrows(DuplicateKeyException.class,
                    () -> jdbc.update("INSERT INTO duplicate_probe(value) VALUES (?)", "same"));
        }
    }

    @Test
    void rejectsRowsThatReferenceMissingParents() throws Exception {
        try (ApplicationInstanceLock lock = new ApplicationInstanceLock(dataRoot.toString());
             HikariDataSource dataSource = (HikariDataSource) new SqlitePersistenceConfiguration()
                     .dataSource(dataRoot.toString(), lock)) {
            JdbcTemplate jdbc = new SqlitePersistenceConfiguration().jdbcTemplate(dataSource);
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                    "INSERT INTO sessions (id,project_id,title,created_at,version) VALUES (?,?,?,?,?)",
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(), "orphan",
                    SqliteValues.epochMicros(Instant.now()), 0));
        }
    }

    @Test
    void eventSequencesRemainContiguousForConcurrentPublishers() throws Exception {
        try (ApplicationInstanceLock lock = new ApplicationInstanceLock(dataRoot.toString());
             HikariDataSource dataSource = (HikariDataSource) new SqlitePersistenceConfiguration()
                     .dataSource(dataRoot.toString(), lock)) {
            JdbcTemplate jdbc = new SqlitePersistenceConfiguration().jdbcTemplate(dataSource);
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
            SqliteEventSink sink = new SqliteEventSink(jdbc, new ObjectMapper());
            UUID experiment = seedExperiment(jdbc);
            int publishers = 8;
            int perPublisher = 25;
            CountDownLatch ready = new CountDownLatch(publishers);
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < publishers; i++) {
                    executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        for (int n = 0; n < perPublisher; n++) sink.publish(experiment, "TEST", Map.of("n", n));
                        return null;
                    });
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
            }
            List<Long> sequences = sink.after(experiment, 0).stream().map(RunEvent::sequence).toList();
            assertEquals(publishers * perPublisher, sequences.size());
            assertEquals(java.util.stream.LongStream.rangeClosed(1, publishers * perPublisher).boxed().toList(), sequences);
        }
    }

    @Test
    void boundsPersistedEventsPerExperimentWithoutResettingSequence() throws Exception {
        try (ApplicationInstanceLock lock = new ApplicationInstanceLock(dataRoot.toString());
             HikariDataSource dataSource = (HikariDataSource) new SqlitePersistenceConfiguration()
                     .dataSource(dataRoot.toString(), lock)) {
            JdbcTemplate jdbc = new SqlitePersistenceConfiguration().jdbcTemplate(dataSource);
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
            UUID experiment = seedExperiment(jdbc);
            // Use the minimum supported retention in order to exercise the
            // trim boundary without making this persistence test slow.
            SqliteEventSink sink = new SqliteEventSink(jdbc, new ObjectMapper(), 100);

            for (int index = 1; index <= 105; index++) {
                sink.publish(experiment, "TEST", Map.of("n", index));
            }

            assertEquals(100L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM run_events WHERE experiment_id=?", Long.class, experiment.toString()));
            assertEquals(java.util.stream.LongStream.rangeClosed(6, 105).boxed().toList(),
                    sink.after(experiment, 0).stream().map(RunEvent::sequence).toList());
            assertEquals(105L, sink.latestSequence(experiment));

            RunEvent next = sink.publish(experiment, "TEST", Map.of("n", 106));
            assertEquals(106L, next.sequence());
            assertEquals(100L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM run_events WHERE experiment_id=?", Long.class, experiment.toString()));
        }
    }

    @Test
    void eventReadsArePagedAtFiveHundredRows() throws Exception {
        try (ApplicationInstanceLock lock = new ApplicationInstanceLock(dataRoot.toString());
             HikariDataSource dataSource = (HikariDataSource) new SqlitePersistenceConfiguration()
                     .dataSource(dataRoot.toString(), lock)) {
            JdbcTemplate jdbc = new SqlitePersistenceConfiguration().jdbcTemplate(dataSource);
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
            UUID experiment = seedExperiment(jdbc);
            SqliteEventSink sink = new SqliteEventSink(jdbc, new ObjectMapper(), 1_000);

            for (int index = 1; index <= 600; index++) {
                sink.publish(experiment, "TEST", Map.of("n", index));
            }

            List<RunEvent> firstPage = sink.after(experiment, 0);
            List<RunEvent> secondPage = sink.after(experiment, firstPage.getLast().sequence());
            assertEquals(500, firstPage.size());
            assertEquals(1L, firstPage.getFirst().sequence());
            assertEquals(500L, firstPage.getLast().sequence());
            assertEquals(100, secondPage.size());
            assertEquals(501L, secondPage.getFirst().sequence());
            assertEquals(600L, secondPage.getLast().sequence());
        }
    }

    @Test
    void accountSettingsAndEncryptedCredentialSurviveRestart() {
        UUID userId = UUID.randomUUID();
        String plaintextKey = "restart-secret-key";
        Instant now = Instant.parse("2026-08-29T01:02:03.123456Z");

        try (ApplicationInstanceLock lock = new ApplicationInstanceLock(dataRoot.toString());
             HikariDataSource dataSource = (HikariDataSource) new SqlitePersistenceConfiguration()
                     .dataSource(dataRoot.toString(), lock)) {
            JdbcTemplate jdbc = new SqlitePersistenceConfiguration().jdbcTemplate(dataSource);
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
            jdbc.update("INSERT INTO users (id,username,password_hash,created_at,version) VALUES (?,?,?,?,?)",
                    userId.toString(), "restart-user", "hash", SqliteValues.epochMicros(now), 0);
            SecretCipher cipher = new SecretCipher(dataRoot.toString(), lock);
            SqliteUserSettingsRepository repository = new SqliteUserSettingsRepository(jdbc, cipher);
            repository.save(new UserSettings(userId, "dark", "zh-CN", "https://models.example/v1",
                    "demo-model", plaintextKey, 20, 600, 80_000, now, 0));

            String ciphertext = jdbc.queryForObject(
                    "SELECT model_api_key_ciphertext FROM user_settings WHERE user_id=?",
                    String.class, userId.toString());
            assertNotEquals(plaintextKey, ciphertext);
        }

        try (ApplicationInstanceLock lock = new ApplicationInstanceLock(dataRoot.toString());
             HikariDataSource dataSource = (HikariDataSource) new SqlitePersistenceConfiguration()
                     .dataSource(dataRoot.toString(), lock)) {
            JdbcTemplate jdbc = new SqlitePersistenceConfiguration().jdbcTemplate(dataSource);
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
            SqliteUserSettingsRepository repository = new SqliteUserSettingsRepository(
                    jdbc, new SecretCipher(dataRoot.toString(), lock));
            UserSettings restored = repository.findByUserId(userId).orElseThrow();

            assertEquals("dark", restored.theme());
            assertEquals("https://models.example/v1", restored.modelEndpoint());
            assertEquals("demo-model", restored.modelName());
            assertEquals(plaintextKey, restored.modelApiKey());
        }
    }

    private UUID seedExperiment(JdbcTemplate jdbc) {
        long now = SqliteValues.epochMicros(Instant.parse("2026-08-29T01:02:03.123456Z"));
        UUID user = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        UUID experiment = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id,username,password_hash,created_at,version) VALUES (?,?,?,?,?)",
                user.toString(), "event-user-" + user, "hash", now, 0);
        jdbc.update("INSERT INTO projects (id,owner_id,name,canonical_path,canonical_path_key,verification_commands,created_at,version) VALUES (?,?,?,?,?,?,?,?)",
                project.toString(), user.toString(), "event-project", "D:/event-project",
                "d:/event-project", "[]", now, 0);
        jdbc.update("INSERT INTO sessions (id,project_id,title,created_at,version) VALUES (?,?,?,?,?)",
                session.toString(), project.toString(), "event-session", now, 0);
        jdbc.update("INSERT INTO experiments (id,project_id,session_id,task,created_at,status,version) VALUES (?,?,?,?,?,?,?)",
                experiment.toString(), project.toString(), session.toString(), "event-test", now, "RUNNING", 0);
        return experiment;
    }
}
