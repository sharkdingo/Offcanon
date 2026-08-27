package com.pico.infrastructure.mysql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pico.port.SnapshotRepository;
import com.pico.workspace.domain.Snapshot;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("mysql")
public class JdbcSnapshotRepository implements SnapshotRepository {
    private static final String UPSERT = "INSERT INTO snapshots (id,project_id,fingerprint,materialized_path,captured_at,included_files,excluded_files) VALUES (?,?,?,?,?,?,?) "
            + "ON DUPLICATE KEY UPDATE fingerprint=VALUES(fingerprint), materialized_path=VALUES(materialized_path), included_files=VALUES(included_files), excluded_files=VALUES(excluded_files)";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcSnapshotRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Snapshot save(Snapshot snapshot) {
        jdbc.update(UPSERT, snapshot.id().toString(), snapshot.projectId().toString(), snapshot.fingerprint(),
                snapshot.materializedPath().toString(), Timestamp.from(snapshot.capturedAt()),
                json(snapshot.includedFiles()), json(snapshot.excludedFiles()));
        return snapshot;
    }

    @Override
    public Optional<Snapshot> findById(UUID id) {
        return jdbc.query("SELECT * FROM snapshots WHERE id=?", this::map, id.toString()).stream().findFirst();
    }

    private Snapshot map(ResultSet rs, int row) throws SQLException {
        return new Snapshot(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("project_id")),
                rs.getString("fingerprint"), Path.of(rs.getString("materialized_path")),
                rs.getTimestamp("captured_at").toInstant(), jsonList(rs.getString("included_files")), jsonExcluded(rs.getString("excluded_files")));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to encode snapshot metadata", error);
        }
    }

    private List<String> jsonList(String value) {
        try {
            return mapper.readValue(value == null ? "[]" : value, new TypeReference<>() {
            });
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to decode snapshot files", error);
        }
    }

    private List<Snapshot.ExcludedPath> jsonExcluded(String value) {
        try {
            return mapper.readValue(value == null ? "[]" : value, new TypeReference<>() {
            });
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to decode snapshot exclusions", error);
        }
    }
}
