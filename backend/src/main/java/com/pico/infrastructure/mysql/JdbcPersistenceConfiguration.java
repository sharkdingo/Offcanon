package com.pico.infrastructure.mysql;

import com.pico.project.domain.CanonicalPathIdentity;
import com.pico.port.SnapshotPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
@Profile("mysql")
public class JdbcPersistenceConfiguration {
    @Bean
    public DataSource dataSource(
            @Value("${pico.mysql.url:${PICO_MYSQL_URL:jdbc:mysql://localhost:3306/pico?createDatabaseIfNotExist=true&serverTimezone=UTC}}") String url,
            @Value("${pico.mysql.username:${PICO_MYSQL_USERNAME:pico}}") String username,
            @Value("${pico.mysql.password:${PICO_MYSQL_PASSWORD:}}") String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean
    public org.springframework.boot.CommandLineRunner schemaInitializer(DataSource dataSource, SnapshotPort snapshots) {
        return ignored -> {
            new ResourceDatabasePopulator(new ClassPathResource("schema-mysql.sql")).execute(dataSource);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            // CREATE TABLE IF NOT EXISTS does not evolve a table created by an earlier milestone.
            ensureColumn(jdbc, "projects", "canonical_path_key",
                    "ALTER TABLE projects ADD COLUMN canonical_path_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER canonical_path");
            migrateCanonicalProjectIdentity(jdbc, snapshots);
            ensureColumn(jdbc, "experiments", "result_snapshot_id",
                    "ALTER TABLE experiments ADD COLUMN result_snapshot_id CHAR(36) NULL AFTER base_snapshot_id");
            ensureColumn(jdbc, "evidence", "environment_profile",
                    "ALTER TABLE evidence ADD COLUMN environment_profile VARCHAR(64) NOT NULL DEFAULT 'unknown' AFTER trusted");
            ensureColumn(jdbc, "evidence", "cancelled",
                    "ALTER TABLE evidence ADD COLUMN cancelled BOOLEAN NOT NULL DEFAULT FALSE AFTER environment_profile");
            ensureColumn(jdbc, "promotion_journal", "touched_files",
                    "ALTER TABLE promotion_journal ADD COLUMN touched_files JSON NULL AFTER candidate_path");
            ensureColumn(jdbc, "promotion_journal", "preimage_hashes",
                    "ALTER TABLE promotion_journal ADD COLUMN preimage_hashes JSON NULL AFTER touched_files");
            ensureColumn(jdbc, "promotion_journal", "postimage_hashes",
                    "ALTER TABLE promotion_journal ADD COLUMN postimage_hashes JSON NULL AFTER preimage_hashes");
            jdbc.update("UPDATE promotion_journal SET touched_files=JSON_ARRAY() WHERE touched_files IS NULL");
            jdbc.update("UPDATE promotion_journal SET preimage_hashes=JSON_OBJECT() WHERE preimage_hashes IS NULL");
            jdbc.update("UPDATE promotion_journal SET postimage_hashes=JSON_OBJECT() WHERE postimage_hashes IS NULL");
            jdbc.execute("ALTER TABLE promotion_journal MODIFY touched_files JSON NOT NULL");
            jdbc.execute("ALTER TABLE promotion_journal MODIFY preimage_hashes JSON NOT NULL");
            jdbc.execute("ALTER TABLE promotion_journal MODIFY postimage_hashes JSON NOT NULL");
        };
    }

    private void ensureColumn(JdbcTemplate jdbc, String table, String column, String alterSql) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",
                Integer.class, table, column);
        if (count != null && count == 0) {
            jdbc.execute(alterSql);
        }
    }

    private void migrateCanonicalProjectIdentity(JdbcTemplate jdbc, SnapshotPort snapshots) {
        List<StoredProjectPath> projects = jdbc.query("SELECT id, canonical_path FROM projects",
                (result, row) -> new StoredProjectPath(result.getString("id"), result.getString("canonical_path")));
        Map<String, List<StoredProjectPath>> byIdentity = new LinkedHashMap<>();
        Map<String, Path> resolvedPaths = new LinkedHashMap<>();
        for (StoredProjectPath project : projects) {
            Path resolved = resolveHistoricalPath(project, snapshots);
            String identity = CanonicalPathIdentity.key(resolved);
            resolvedPaths.put(project.id(), resolved);
            byIdentity.computeIfAbsent(identity, ignored -> new java.util.ArrayList<>()).add(project);
        }
        List<List<StoredProjectPath>> duplicates = byIdentity.values().stream()
                .filter(group -> group.size() > 1).toList();
        if (!duplicates.isEmpty()) {
            String detail = duplicates.stream()
                    .map(group -> group.stream().map(item -> item.id() + "=" + item.path()).toList().toString())
                    .collect(java.util.stream.Collectors.joining("; "));
            throw new IllegalStateException("Cannot enforce unique canonical Git roots because historical projects overlap: "
                    + detail + ". Remove or consolidate the duplicate project rows, then restart PICO.");
        }
        for (StoredProjectPath project : projects) {
            Path resolved = resolvedPaths.get(project.id());
            jdbc.update("UPDATE projects SET canonical_path=?, canonical_path_key=? WHERE id=?",
                    resolved.toString(), CanonicalPathIdentity.key(resolved), project.id());
        }
        jdbc.execute("ALTER TABLE projects MODIFY canonical_path_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL");
        ensureIndex(jdbc, "projects", "uk_projects_canonical_path_key",
                "ALTER TABLE projects ADD UNIQUE INDEX uk_projects_canonical_path_key (canonical_path_key)");
    }

    private Path resolveHistoricalPath(StoredProjectPath project, SnapshotPort snapshots) {
        Path path = Path.of(project.path()).toAbsolutePath().normalize();
        try {
            return snapshots.resolveProjectRoot(path);
        } catch (RuntimeException error) {
            throw new IllegalStateException("Historical project " + project.id() + " has an invalid canonical Git root: "
                    + project.path() + ". Repair or remove that row, then restart PICO.", error);
        }
    }

    private void ensureIndex(JdbcTemplate jdbc, String table, String index, String alterSql) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name=?",
                Integer.class, table, index);
        if (count != null && count == 0) {
            jdbc.execute(alterSql);
        }
    }

    private record StoredProjectPath(String id, String path) {
    }
}
