package com.pico.infrastructure.mysql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pico.port.ProjectRepository;
import com.pico.project.domain.Project;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("mysql")
public class JdbcProjectRepository implements ProjectRepository {
    private static final String UPSERT = "INSERT INTO projects (id,name,canonical_path,verification_commands,created_at,version) VALUES (?,?,?,?,?,?) "
            + "ON DUPLICATE KEY UPDATE name=VALUES(name), canonical_path=VALUES(canonical_path), verification_commands=VALUES(verification_commands), version=VALUES(version)";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcProjectRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Project save(Project project) {
        jdbc.update(UPSERT, project.id().toString(), project.name(), project.canonicalPath().toString(),
                json(project.verificationCommands()), Timestamp.from(project.createdAt()), project.version());
        return project;
    }

    @Override
    public Optional<Project> findById(UUID id) {
        return jdbc.query("SELECT * FROM projects WHERE id=?", this::map, id.toString()).stream().findFirst();
    }

    @Override
    public List<Project> findAll() {
        return jdbc.query("SELECT * FROM projects ORDER BY created_at", this::map).stream()
                .sorted(Comparator.comparing(Project::createdAt)).toList();
    }

    private Project map(ResultSet rs, int row) throws SQLException {
        return new Project(UUID.fromString(rs.getString("id")), rs.getString("name"),
                Path.of(rs.getString("canonical_path")), jsonList(rs.getString("verification_commands")),
                instant(rs.getTimestamp("created_at")), rs.getLong("version"));
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp.toInstant();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to encode project metadata", error);
        }
    }

    private List<String> jsonList(String value) {
        try {
            return mapper.readValue(value == null ? "[]" : value, new TypeReference<>() {
            });
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to decode project metadata", error);
        }
    }
}
