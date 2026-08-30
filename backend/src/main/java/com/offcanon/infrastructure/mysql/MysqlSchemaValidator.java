package com.offcanon.infrastructure.mysql;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Verifies the development schema after the idempotent bootstrap script runs.
 *
 * <p>The SQL file deliberately uses {@code IF NOT EXISTS} so a normal restart
 * remains harmless. That clause cannot be used as a schema migration strategy:
 * an old table with a missing column would otherwise be accepted silently. This
 * validator turns that situation into a startup failure and tells the developer
 * to recreate the development database.</p>
 */
final class MysqlSchemaValidator {
    private static final Map<String, Map<String, ColumnSpec>> EXPECTED_COLUMNS = expectedColumns();
    private static final Map<String, Set<String>> EXPECTED_PRIMARY_KEYS = Map.ofEntries(
            entry("projects", "id"),
            entry("users", "id"),
            entry("auth_sessions", "token_hash"),
            entry("user_settings", "user_id"),
            entry("sessions", "id"),
            entry("task_memory_revisions", "id"),
            entry("snapshots", "id"),
            entry("experiments", "id"),
            entry("evidence", "id"),
            entry("run_events", "experiment_id", "sequence"),
            entry("promotion_journal", "promotion_id"));
    private static final Map<String, List<Set<String>>> EXPECTED_UNIQUE_KEYS = Map.ofEntries(
            entryList("projects", set("canonical_path_key")),
            entryList("users", set("username")),
            entryList("task_memory_revisions", set("session_id", "sequence")),
            entryList("run_events", set("event_id")));
    /**
     * Full index contract, including non-unique indexes.  Repository queries
     * rely on these names and column order; accepting an arbitrary equivalent
     * index would make a stale development schema difficult to diagnose.
     */
    private static final Map<String, Map<String, IndexSpec>> EXPECTED_INDEXES = expectedIndexes();

    private MysqlSchemaValidator() {
    }

    static void validate(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String catalog = connection.getCatalog();
            Set<String> tables = readTables(metadata, catalog);
            Map<String, Map<String, ColumnMetadata>> columns = readColumns(metadata, catalog);
            Map<String, Set<String>> primaryKeys = new HashMap<>();
            Map<String, List<Set<String>>> uniqueKeys = new HashMap<>();
            Map<String, Map<String, IndexMetadata>> indexes = new HashMap<>();
            for (String table : EXPECTED_COLUMNS.keySet()) {
                primaryKeys.put(table, readPrimaryKey(metadata, catalog, table));
                uniqueKeys.put(table, readUniqueKeys(metadata, catalog, table));
                indexes.put(table, readIndexes(metadata, catalog, table));
            }
            validateMetadata(tables, columns, primaryKeys, uniqueKeys, indexes);
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to inspect the Offcanon MySQL schema; startup aborted", error);
        }
    }

    /** Package-private for focused tests without a live MySQL server. */
    static void validateMetadata(Set<String> actualTables,
                                 Map<String, Map<String, ColumnMetadata>> actualColumns,
                                 Map<String, Set<String>> actualPrimaryKeys,
                                 Map<String, List<Set<String>>> actualUniqueKeys) {
        validateMetadata(actualTables, actualColumns, actualPrimaryKeys, actualUniqueKeys, null);
    }

    /** Package-private overload that also verifies the complete named index contract. */
    static void validateMetadata(Set<String> actualTables,
                                 Map<String, Map<String, ColumnMetadata>> actualColumns,
                                 Map<String, Set<String>> actualPrimaryKeys,
                                 Map<String, List<Set<String>>> actualUniqueKeys,
                                 Map<String, Map<String, IndexMetadata>> actualIndexes) {
        Set<String> tables = normalizeSet(actualTables);
        List<String> problems = new ArrayList<>();
        Set<String> expectedTables = EXPECTED_COLUMNS.keySet().stream()
                .map(MysqlSchemaValidator::normalize)
                .collect(Collectors.toSet());
        Set<String> unexpectedTables = new LinkedHashSet<>(tables);
        unexpectedTables.removeAll(expectedTables);
        if (!unexpectedTables.isEmpty()) {
            problems.add("unexpected table(s) " + unexpectedTables);
        }
        for (String expectedTable : EXPECTED_COLUMNS.keySet()) {
            if (!tables.contains(expectedTable)) {
                problems.add("missing table " + expectedTable);
                continue;
            }
            Map<String, ColumnMetadata> columns = normalizeColumns(actualColumns.get(expectedTable));
            Set<String> unexpectedColumns = new LinkedHashSet<>(columns.keySet());
            unexpectedColumns.removeAll(EXPECTED_COLUMNS.get(expectedTable).keySet());
            if (!unexpectedColumns.isEmpty()) {
                problems.add("unexpected column(s) " + expectedTable + unexpectedColumns);
            }
            for (Map.Entry<String, ColumnSpec> entry : EXPECTED_COLUMNS.get(expectedTable).entrySet()) {
                ColumnMetadata actual = columns.get(entry.getKey());
                if (actual == null) {
                    problems.add("missing column " + expectedTable + "." + entry.getKey());
                    continue;
                }
                ColumnSpec expected = entry.getValue();
                if (!expected.types().contains(normalizeType(actual.typeName()))) {
                    problems.add("wrong type for " + expectedTable + "." + entry.getKey()
                            + " (expected " + expected.types() + ", got " + actual.typeName() + ")");
                }
                if (expected.nullable() != actual.nullable()) {
                    problems.add("wrong nullability for " + expectedTable + "." + entry.getKey());
                }
                if (expected.size() != null && actual.size() != null
                        && !expected.size().equals(actual.size())) {
                    problems.add("wrong size for " + expectedTable + "." + entry.getKey()
                            + " (expected " + expected.size() + ", got " + actual.size() + ")");
                }
            }
            Set<String> expectedPrimary = EXPECTED_PRIMARY_KEYS.getOrDefault(expectedTable, Set.of());
            Set<String> primary = normalizeSet(actualPrimaryKeys == null ? null : actualPrimaryKeys.get(expectedTable));
            if (!expectedPrimary.equals(primary)) {
                problems.add("wrong primary key for " + expectedTable
                        + " (expected " + expectedPrimary + ", got " + primary + ")");
            }
            List<Set<String>> expectedUnique = EXPECTED_UNIQUE_KEYS.getOrDefault(expectedTable, List.of());
            List<Set<String>> unique = actualUniqueKeys == null
                    ? List.of() : actualUniqueKeys.getOrDefault(expectedTable, List.of()).stream()
                    .map(MysqlSchemaValidator::normalizeSet).toList();
            for (Set<String> required : expectedUnique) {
                if (!unique.contains(required) && !required.equals(primary)) {
                    problems.add("missing unique key " + expectedTable + required);
                }
            }
            if (actualIndexes != null) {
                validateIndexes(expectedTable, actualIndexes.get(expectedTable), problems);
            }
        }
        if (!problems.isEmpty()) {
            String detail = problems.size() > 20
                    ? String.join("; ", problems.subList(0, 20)) + "; ..."
                    : String.join("; ", problems);
            throw new IllegalStateException("Offcanon MySQL schema does not match schema-mysql.sql: "
                    + detail + ". Recreate the development database; runtime migration is not supported.");
        }
    }

    private static Set<String> readTables(DatabaseMetaData metadata, String catalog) throws SQLException {
        Set<String> tables = new HashSet<>();
        try (ResultSet result = metadata.getTables(catalog, null, "%", new String[]{"TABLE"})) {
            while (result.next()) tables.add(normalize(result.getString("TABLE_NAME")));
        }
        return tables;
    }

    private static Map<String, Map<String, ColumnMetadata>> readColumns(DatabaseMetaData metadata,
                                                                         String catalog) throws SQLException {
        Map<String, Map<String, ColumnMetadata>> columns = new HashMap<>();
        try (ResultSet result = metadata.getColumns(catalog, null, "%", "%")) {
            while (result.next()) {
                String table = normalize(result.getString("TABLE_NAME"));
                if (!EXPECTED_COLUMNS.containsKey(table)) continue;
                String nullable = result.getString("IS_NULLABLE");
                boolean isNullable = "YES".equalsIgnoreCase(nullable)
                        || (nullable == null && result.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                int columnSize = result.getInt("COLUMN_SIZE");
                Integer size = result.wasNull() ? null : columnSize;
                columns.computeIfAbsent(table, ignored -> new HashMap<>())
                        .put(normalize(result.getString("COLUMN_NAME")),
                                new ColumnMetadata(result.getString("TYPE_NAME"), isNullable, size));
            }
        }
        return columns;
    }

    private static Set<String> readPrimaryKey(DatabaseMetaData metadata, String catalog, String table)
            throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (ResultSet result = metadata.getPrimaryKeys(catalog, null, table)) {
            while (result.next()) columns.add(normalize(result.getString("COLUMN_NAME")));
        }
        return columns;
    }

    private static List<Set<String>> readUniqueKeys(DatabaseMetaData metadata, String catalog, String table)
            throws SQLException {
        Map<String, Set<String>> indexes = new LinkedHashMap<>();
        Map<String, Boolean> unique = new HashMap<>();
        try (ResultSet result = metadata.getIndexInfo(catalog, null, table, false, false)) {
            while (result.next()) {
                String name = result.getString("INDEX_NAME");
                String column = result.getString("COLUMN_NAME");
                if (name == null || column == null || result.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }
                String normalizedName = normalize(name);
                indexes.computeIfAbsent(normalizedName, ignored -> new LinkedHashSet<>()).add(normalize(column));
                unique.put(normalizedName, !result.getBoolean("NON_UNIQUE"));
            }
        }
        return indexes.entrySet().stream()
                .filter(entry -> Boolean.TRUE.equals(unique.get(entry.getKey())))
                .map(Map.Entry::getValue)
                .toList();
    }

    private static Map<String, IndexMetadata> readIndexes(DatabaseMetaData metadata, String catalog, String table)
            throws SQLException {
        Map<String, List<IndexedColumn>> indexes = new LinkedHashMap<>();
        Map<String, Boolean> unique = new HashMap<>();
        try (ResultSet result = metadata.getIndexInfo(catalog, null, table, false, false)) {
            while (result.next()) {
                String name = result.getString("INDEX_NAME");
                String column = result.getString("COLUMN_NAME");
                short type = result.getShort("TYPE");
                if (name == null || column == null || type == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }
                String normalizedName = normalize(name);
                short ordinal = result.getShort("ORDINAL_POSITION");
                indexes.computeIfAbsent(normalizedName, ignored -> new ArrayList<>())
                        .add(new IndexedColumn(ordinal, normalize(column)));
                unique.put(normalizedName, !result.getBoolean("NON_UNIQUE"));
            }
        }
        Map<String, IndexMetadata> result = new LinkedHashMap<>();
        indexes.forEach((name, columns) -> {
            columns.sort(java.util.Comparator.comparingInt(IndexedColumn::ordinal));
            result.put(name, new IndexMetadata(name, Boolean.TRUE.equals(unique.get(name)),
                    columns.stream().map(IndexedColumn::column).toList()));
        });
        return result;
    }

    private static void validateIndexes(String table, Map<String, IndexMetadata> actualIndexes,
                                        List<String> problems) {
        Map<String, IndexMetadata> actual = new LinkedHashMap<>();
        if (actualIndexes != null) {
            actualIndexes.forEach((name, metadata) -> actual.put(normalize(name), metadata));
        }
        Set<String> expectedNames = EXPECTED_INDEXES.getOrDefault(table, Map.of()).keySet().stream()
                .map(MysqlSchemaValidator::normalize).collect(Collectors.toSet());
        Set<String> actualNames = actual.keySet().stream()
                .map(MysqlSchemaValidator::normalize).collect(Collectors.toSet());
        Set<String> missing = new LinkedHashSet<>(expectedNames);
        missing.removeAll(actualNames);
        if (!missing.isEmpty()) problems.add("missing index(es) " + table + missing);
        Set<String> unexpected = new LinkedHashSet<>(actualNames);
        unexpected.removeAll(expectedNames);
        if (!unexpected.isEmpty()) problems.add("unexpected index(es) " + table + unexpected);
        for (Map.Entry<String, IndexSpec> entry : EXPECTED_INDEXES.getOrDefault(table, Map.of()).entrySet()) {
            IndexMetadata observed = actual.get(normalize(entry.getKey()));
            if (observed == null) continue;
            IndexSpec expected = entry.getValue();
            if (expected.unique() != observed.unique()
                    || !expected.columns().equals(observed.columns())) {
                problems.add("wrong index " + table + "." + entry.getKey()
                        + " (expected " + (expected.unique() ? "UNIQUE " : "") + expected.columns()
                        + ", got " + (observed.unique() ? "UNIQUE " : "") + observed.columns() + ")");
            }
        }
    }

    private static Map<String, Map<String, ColumnSpec>> expectedColumns() {
        Map<String, Map<String, ColumnSpec>> tables = new LinkedHashMap<>();
        tables.put("projects", columns(
                sized("id", false, 36, "CHAR"), sized("owner_id", false, 36, "CHAR"), sized("name", false, 255, "VARCHAR"),
                c("canonical_path", false, "TEXT", "MEDIUMTEXT", "LONGTEXT"), sized("canonical_path_key", false, 64, "CHAR"),
                c("verification_commands", false, "JSON"), c("created_at", false, "TIMESTAMP"), c("version", false, "BIGINT")));
        tables.put("users", columns(
                sized("id", false, 36, "CHAR"), sized("username", false, 64, "VARCHAR"), sized("password_hash", false, 512, "VARCHAR"),
                c("created_at", false, "TIMESTAMP"), c("version", false, "BIGINT")));
        tables.put("auth_sessions", columns(
                sized("token_hash", false, 43, "CHAR"), sized("user_id", false, 36, "CHAR"), c("created_at", false, "TIMESTAMP"),
                c("expires_at", false, "TIMESTAMP")));
        tables.put("user_settings", columns(
                sized("user_id", false, 36, "CHAR"), sized("theme", false, 16, "VARCHAR"), sized("locale", false, 32, "VARCHAR"),
                sized("model_endpoint", false, 2048, "VARCHAR"), sized("model_name", false, 200, "VARCHAR"), c("agent_max_steps", false, "INT"),
                c("agent_run_timeout_seconds", false, "BIGINT"), c("context_limit_chars", false, "INT"),
                c("updated_at", false, "TIMESTAMP"), c("version", false, "BIGINT")));
        tables.put("sessions", columns(
                sized("id", false, 36, "CHAR"), sized("project_id", false, 36, "CHAR"), sized("title", false, 255, "VARCHAR"),
                c("created_at", false, "TIMESTAMP"), c("version", false, "BIGINT")));
        tables.put("task_memory_revisions", columns(
                sized("id", false, 36, "CHAR"), sized("project_id", false, 36, "CHAR"), sized("session_id", false, 36, "CHAR"),
                sized("source_experiment_id", false, 36, "CHAR"), sized("source_snapshot_id", false, 36, "CHAR"), sized("source_fingerprint", false, 255, "VARCHAR"),
                sized("memory_kind", false, 32, "VARCHAR"), c("content", false, "TEXT", "MEDIUMTEXT", "LONGTEXT"),
                c("source_evidence_ids", false, "JSON"), sized("origin", false, 32, "VARCHAR"), sized("trust", false, 32, "VARCHAR"),
                sized("status", false, 32, "VARCHAR"), c("supersedes_ids", false, "JSON"), c("created_at", false, "TIMESTAMP"),
                c("sequence", false, "BIGINT")));
        tables.put("snapshots", columns(
                sized("id", false, 36, "CHAR"), sized("project_id", false, 36, "CHAR"), sized("fingerprint", false, 255, "VARCHAR"),
                c("materialized_path", false, "TEXT", "MEDIUMTEXT", "LONGTEXT"), c("captured_at", false, "TIMESTAMP"),
                c("included_files", false, "JSON"), c("excluded_files", false, "JSON")));
        tables.put("experiments", columns(
                sized("id", false, 36, "CHAR"), sized("project_id", false, 36, "CHAR"), sized("session_id", false, 36, "CHAR"),
                sized("continued_from_experiment_id", true, 36, "CHAR"), c("task", false, "TEXT", "MEDIUMTEXT", "LONGTEXT"),
                c("created_at", false, "TIMESTAMP"), sized("status", false, 48, "VARCHAR"), sized("base_snapshot_id", true, 36, "CHAR"),
                sized("result_snapshot_id", true, 36, "CHAR"), c("workspace_path", true, "TEXT", "MEDIUMTEXT", "LONGTEXT"),
                c("agent_summary", true, "TEXT", "MEDIUMTEXT", "LONGTEXT"), c("failure_reason", true, "TEXT", "MEDIUMTEXT", "LONGTEXT"),
                c("verification_passed", true, "BOOLEAN", "TINYINT", "BIT"), c("version", false, "BIGINT")));
        tables.put("evidence", columns(
                sized("id", false, 36, "CHAR"), sized("experiment_id", false, 36, "CHAR"), sized("snapshot_id", false, 36, "CHAR"),
                sized("kind", false, 64, "VARCHAR"), c("command", false, "TEXT", "MEDIUMTEXT", "LONGTEXT"),
                c("cwd", false, "TEXT", "MEDIUMTEXT", "LONGTEXT"), c("exit_code", false, "INT"),
                c("stdout", false, "MEDIUMTEXT", "TEXT", "LONGTEXT"), c("stderr", false, "MEDIUMTEXT", "TEXT", "LONGTEXT"),
                c("started_at", false, "TIMESTAMP"), c("completed_at", false, "TIMESTAMP"), c("duration_millis", false, "BIGINT"),
                c("timed_out", false, "BOOLEAN", "TINYINT", "BIT"), c("trusted", false, "BOOLEAN", "TINYINT", "BIT"),
                sized("environment_profile", false, 64, "VARCHAR"), c("cancelled", false, "BOOLEAN", "TINYINT", "BIT")));
        tables.put("run_events", columns(
                sized("event_id", false, 36, "CHAR"), sized("experiment_id", false, 36, "CHAR"), c("sequence", false, "BIGINT"),
                sized("type", false, 96, "VARCHAR"), c("event_timestamp", false, "TIMESTAMP"), c("payload", false, "JSON")));
        tables.put("promotion_journal", columns(
                sized("promotion_id", false, 36, "CHAR"), sized("experiment_id", false, 36, "CHAR"), sized("project_id", false, 36, "CHAR"),
                sized("base_fingerprint", false, 255, "VARCHAR"), sized("candidate_fingerprint", false, 255, "VARCHAR"),
                c("candidate_path", false, "TEXT", "MEDIUMTEXT", "LONGTEXT"), c("touched_files", false, "JSON"),
                c("preimage_hashes", false, "JSON"), c("postimage_hashes", false, "JSON"), c("phase", false, "VARCHAR"),
                sized("owner_id", false, 128, "VARCHAR"), c("lease_until", false, "TIMESTAMP"), c("created_at", false, "TIMESTAMP"),
                c("updated_at", false, "TIMESTAMP"), sized("resulting_fingerprint", true, 255, "VARCHAR"),
                c("failure_reason", true, "TEXT", "MEDIUMTEXT", "LONGTEXT"), c("version", false, "BIGINT")));
        return Collections.unmodifiableMap(tables);
    }

    private static Map<String, ColumnSpec> columns(ColumnSpecEntry... entries) {
        Map<String, ColumnSpec> result = new LinkedHashMap<>();
        for (ColumnSpecEntry entry : entries) result.put(entry.name(), entry.spec());
        return Collections.unmodifiableMap(result);
    }

    private static ColumnSpecEntry c(String name, boolean nullable, String... types) {
        return new ColumnSpecEntry(name, new ColumnSpec(Set.of(types), nullable, null));
    }

    private static ColumnSpecEntry sized(String name, boolean nullable, int size, String... types) {
        return new ColumnSpecEntry(name, new ColumnSpec(Set.of(types), nullable, size));
    }

    private static Map<String, ColumnMetadata> normalizeColumns(Map<String, ColumnMetadata> columns) {
        if (columns == null) return Map.of();
        Map<String, ColumnMetadata> normalized = new HashMap<>();
        columns.forEach((name, value) -> normalized.put(normalize(name), value));
        return normalized;
    }

    private static Set<String> normalizeSet(Set<String> values) {
        if (values == null) return Set.of();
        return values.stream().map(MysqlSchemaValidator::normalize).collect(Collectors.toUnmodifiableSet());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeType(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static Map.Entry<String, Set<String>> entry(String table, String... columns) {
        return Map.entry(table, Set.of(columns));
    }

    private static Map.Entry<String, List<Set<String>>> entryList(String table, Set<String>... keys) {
        return Map.entry(table, List.of(keys));
    }

    private static Set<String> set(String... values) {
        return Set.of(values);
    }

    private static Map<String, IndexSpec> indexes(IndexSpecEntry... entries) {
        Map<String, IndexSpec> result = new LinkedHashMap<>();
        for (IndexSpecEntry entry : entries) result.put(entry.name(), entry.spec());
        return Collections.unmodifiableMap(result);
    }

    private static IndexSpecEntry i(String name, boolean unique, String... columns) {
        return new IndexSpecEntry(name, new IndexSpec(unique,
                List.of(columns).stream().map(MysqlSchemaValidator::normalize).toList()));
    }

    private static Map<String, Map<String, IndexSpec>> expectedIndexes() {
        Map<String, Map<String, IndexSpec>> tables = new LinkedHashMap<>();
        tables.put("projects", indexes(
                i("PRIMARY", true, "id"),
                i("uk_projects_canonical_path_key", true, "canonical_path_key")));
        tables.put("users", indexes(
                i("PRIMARY", true, "id"),
                i("uk_users_username", true, "username")));
        tables.put("auth_sessions", indexes(
                i("PRIMARY", true, "token_hash"),
                i("idx_auth_sessions_user", false, "user_id"),
                i("idx_auth_sessions_expiry", false, "expires_at")));
        tables.put("user_settings", indexes(i("PRIMARY", true, "user_id")));
        tables.put("sessions", indexes(
                i("PRIMARY", true, "id"),
                i("idx_sessions_project", false, "project_id")));
        tables.put("task_memory_revisions", indexes(
                i("PRIMARY", true, "id"),
                i("uk_task_memory_session_sequence", true, "session_id", "sequence"),
                i("idx_task_memory_session", false, "session_id", "sequence"),
                i("idx_task_memory_project", false, "project_id", "created_at"),
                i("idx_task_memory_experiment", false, "source_experiment_id"),
                i("idx_task_memory_snapshot", false, "source_snapshot_id")));
        tables.put("snapshots", indexes(
                i("PRIMARY", true, "id"),
                i("idx_snapshots_project", false, "project_id")));
        tables.put("experiments", indexes(
                i("PRIMARY", true, "id"),
                i("idx_experiments_project", false, "project_id"),
                i("idx_experiments_session_status", false, "session_id", "status"),
                i("idx_experiments_continued_from", false, "continued_from_experiment_id")));
        tables.put("evidence", indexes(
                i("PRIMARY", true, "id"),
                i("idx_evidence_experiment", false, "experiment_id", "started_at")));
        tables.put("run_events", indexes(
                i("PRIMARY", true, "experiment_id", "sequence"),
                i("event_id", true, "event_id"),
                i("idx_run_events_experiment", false, "experiment_id", "sequence")));
        tables.put("promotion_journal", indexes(
                i("PRIMARY", true, "promotion_id"),
                i("idx_promotion_journal_experiment", false, "experiment_id"),
                i("idx_promotion_journal_open", false, "phase", "lease_until"),
                i("idx_promotion_journal_project_phase", false, "project_id", "phase", "created_at")));
        return Collections.unmodifiableMap(tables);
    }

    private record ColumnSpec(Set<String> types, boolean nullable, Integer size) {
        private ColumnSpec {
            types = types.stream().map(value -> value.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        }
    }

    private record ColumnSpecEntry(String name, ColumnSpec spec) {
    }

    private record IndexSpecEntry(String name, IndexSpec spec) {
    }

    private record IndexSpec(boolean unique, List<String> columns) {
    }

    private record IndexedColumn(short ordinal, String column) {
    }

    record IndexMetadata(String name, boolean unique, List<String> columns) {
        IndexMetadata {
            name = normalize(name);
            columns = columns.stream().map(MysqlSchemaValidator::normalize).toList();
        }
    }

    record ColumnMetadata(String typeName, boolean nullable, Integer size) {
        ColumnMetadata(String typeName, boolean nullable) {
            this(typeName, nullable, null);
        }
    }
}
