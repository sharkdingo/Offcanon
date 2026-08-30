package com.offcanon.infrastructure.mysql;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlSchemaValidatorTest {
    @Test
    void completeSchemaMetadataPasses() {
        Fixture fixture = Fixture.complete();

        assertDoesNotThrow(() -> MysqlSchemaValidator.validateMetadata(
                fixture.tables(), fixture.columns(), fixture.primaryKeys(), fixture.uniqueKeys()));
    }

    @Test
    void missingTableFailsWithRecreateInstruction() {
        Fixture fixture = Fixture.complete();
        Set<String> tables = new HashSet<>(fixture.tables());
        tables.remove("experiments");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> MysqlSchemaValidator.validateMetadata(
                tables, fixture.columns(), fixture.primaryKeys(), fixture.uniqueKeys()));

        assertTrue(error.getMessage().contains("missing table experiments"));
        assertTrue(error.getMessage().contains("Recreate the development database"));
    }

    @Test
    void missingColumnFails() {
        Fixture fixture = Fixture.complete();
        Map<String, Map<String, MysqlSchemaValidator.ColumnMetadata>> columns = copyColumns(fixture.columns());
        columns.get("sessions").remove("title");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> MysqlSchemaValidator.validateMetadata(
                fixture.tables(), columns, fixture.primaryKeys(), fixture.uniqueKeys()));

        assertTrue(error.getMessage().contains("missing column sessions.title"));
    }

    @Test
    void unexpectedTableAndColumnFail() {
        Fixture fixture = Fixture.complete();
        Set<String> tables = new HashSet<>(fixture.tables());
        tables.add("legacy_projects");
        Map<String, Map<String, MysqlSchemaValidator.ColumnMetadata>> columns = copyColumns(fixture.columns());
        columns.get("sessions").put("legacy_flag", new MysqlSchemaValidator.ColumnMetadata("TINYINT", false));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> MysqlSchemaValidator.validateMetadata(
                tables, columns, fixture.primaryKeys(), fixture.uniqueKeys()));

        assertTrue(error.getMessage().contains("unexpected table(s)"));
        assertTrue(error.getMessage().contains("unexpected column(s) sessions"));
    }

    @Test
    void typeAndNullabilityMismatchFails() {
        Fixture fixture = Fixture.complete();
        Map<String, Map<String, MysqlSchemaValidator.ColumnMetadata>> columns = copyColumns(fixture.columns());
        columns.get("users").put("username", new MysqlSchemaValidator.ColumnMetadata("INT", true));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> MysqlSchemaValidator.validateMetadata(
                fixture.tables(), columns, fixture.primaryKeys(), fixture.uniqueKeys()));

        assertTrue(error.getMessage().contains("wrong type for users.username"));
        assertTrue(error.getMessage().contains("wrong nullability for users.username"));
    }

    @Test
    void boundedStringSizeMismatchFails() {
        Fixture fixture = Fixture.complete();
        Map<String, Map<String, MysqlSchemaValidator.ColumnMetadata>> columns = copyColumns(fixture.columns());
        columns.get("users").put("username", new MysqlSchemaValidator.ColumnMetadata("VARCHAR", false, 16));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> MysqlSchemaValidator.validateMetadata(
                fixture.tables(), columns, fixture.primaryKeys(), fixture.uniqueKeys()));

        assertTrue(error.getMessage().contains("wrong size for users.username"));
    }

    @Test
    void primaryAndUniqueKeyMismatchFails() {
        Fixture fixture = Fixture.complete();
        Map<String, Set<String>> primaryKeys = new HashMap<>(fixture.primaryKeys());
        primaryKeys.put("projects", Set.of("owner_id"));
        Map<String, List<Set<String>>> uniqueKeys = new HashMap<>(fixture.uniqueKeys());
        uniqueKeys.put("users", List.of());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> MysqlSchemaValidator.validateMetadata(
                fixture.tables(), fixture.columns(), primaryKeys, uniqueKeys));

        assertTrue(error.getMessage().contains("wrong primary key for projects"));
        assertTrue(error.getMessage().contains("missing unique key users[username]"));
    }

    @Test
    void namedIndexContractRejectsMissingOrReorderedIndex() {
        Fixture fixture = Fixture.complete();
        Map<String, Map<String, MysqlSchemaValidator.IndexMetadata>> indexes = completeIndexes();
        indexes.get("experiments").put("idx_experiments_session_status",
                new MysqlSchemaValidator.IndexMetadata("idx_experiments_session_status", false,
                        List.of("status", "session_id")));
        indexes.get("sessions").remove("idx_sessions_project");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> MysqlSchemaValidator.validateMetadata(
                fixture.tables(), fixture.columns(), fixture.primaryKeys(), fixture.uniqueKeys(), indexes));

        assertTrue(error.getMessage().contains("wrong index experiments.idx_experiments_session_status"));
        assertTrue(error.getMessage().contains("missing index(es) sessions"));
    }

    private static Map<String, Map<String, MysqlSchemaValidator.ColumnMetadata>> copyColumns(
            Map<String, Map<String, MysqlSchemaValidator.ColumnMetadata>> source) {
        Map<String, Map<String, MysqlSchemaValidator.ColumnMetadata>> copy = new HashMap<>();
        source.forEach((table, columns) -> copy.put(table, new HashMap<>(columns)));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, MysqlSchemaValidator.IndexMetadata>> completeIndexes() {
        try {
            Field field = MysqlSchemaValidator.class.getDeclaredField("EXPECTED_INDEXES");
            field.setAccessible(true);
            Map<String, Map<String, ?>> expected = (Map<String, Map<String, ?>>) field.get(null);
            Map<String, Map<String, MysqlSchemaValidator.IndexMetadata>> result = new HashMap<>();
            for (Map.Entry<String, Map<String, ?>> table : expected.entrySet()) {
                Map<String, MysqlSchemaValidator.IndexMetadata> indexes = new HashMap<>();
                for (Map.Entry<String, ?> index : table.getValue().entrySet()) {
                    Method unique = index.getValue().getClass().getDeclaredMethod("unique");
                    Method columns = index.getValue().getClass().getDeclaredMethod("columns");
                    indexes.put(index.getKey(), new MysqlSchemaValidator.IndexMetadata(index.getKey(),
                            (boolean) unique.invoke(index.getValue()),
                            (List<String>) columns.invoke(index.getValue())));
                }
                result.put(table.getKey(), indexes);
            }
            return result;
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Unable to build index validator fixture", error);
        }
    }

    private record Fixture(Set<String> tables,
                           Map<String, Map<String, MysqlSchemaValidator.ColumnMetadata>> columns,
                           Map<String, Set<String>> primaryKeys,
                           Map<String, List<Set<String>>> uniqueKeys) {
        private static Fixture complete() {
            try {
                Class<MysqlSchemaValidator> validator = MysqlSchemaValidator.class;
                Map<?, ?> expectedColumns = readMapField(validator, "EXPECTED_COLUMNS");
                Map<String, Map<String, MysqlSchemaValidator.ColumnMetadata>> columns = new HashMap<>();
                for (Map.Entry<?, ?> tableEntry : expectedColumns.entrySet()) {
                    Map<String, MysqlSchemaValidator.ColumnMetadata> tableColumns = new HashMap<>();
                    Map<?, ?> expectedTableColumns = (Map<?, ?>) tableEntry.getValue();
                    for (Map.Entry<?, ?> columnEntry : expectedTableColumns.entrySet()) {
                        Object spec = columnEntry.getValue();
                        Method types = spec.getClass().getDeclaredMethod("types");
                        Method nullable = spec.getClass().getDeclaredMethod("nullable");
                        @SuppressWarnings("unchecked")
                        Set<String> supportedTypes = (Set<String>) types.invoke(spec);
                        tableColumns.put((String) columnEntry.getKey(),
                                new MysqlSchemaValidator.ColumnMetadata(supportedTypes.iterator().next(),
                                        (boolean) nullable.invoke(spec)));
                    }
                    columns.put((String) tableEntry.getKey(), tableColumns);
                }
                Map<String, Set<String>> primaryKeys = readMapField(validator, "EXPECTED_PRIMARY_KEYS");
                Map<String, List<Set<String>>> uniqueKeys = readMapField(validator, "EXPECTED_UNIQUE_KEYS");
                return new Fixture(columns.keySet(), columns, primaryKeys, uniqueKeys);
            } catch (ReflectiveOperationException error) {
                throw new AssertionError("Unable to build schema validator fixture", error);
            }
        }

        @SuppressWarnings("unchecked")
        private static <T> T readMapField(Class<?> owner, String name) throws ReflectiveOperationException {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(null);
        }
    }
}
