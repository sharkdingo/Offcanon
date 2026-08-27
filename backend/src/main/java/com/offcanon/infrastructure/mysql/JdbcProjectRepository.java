package com.offcanon.infrastructure.mysql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offcanon.port.ProjectRepository;
import com.offcanon.project.domain.CanonicalPathIdentity;
import com.offcanon.project.domain.Project;
import com.offcanon.shared.domain.DomainException;
import org.springframework.dao.DuplicateKeyException;
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
    private static final String INSERT = "INSERT INTO projects (id,owner_id,name,canonical_path,canonical_path_key,verification_commands,created_at,version) VALUES (?,?,?,?,?,?,?,?)";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcProjectRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Project save(Project project) {
        try {
            jdbc.update(INSERT, project.id().toString(), project.ownerId().toString(), project.name(), project.canonicalPath().toString(),
                    CanonicalPathIdentity.key(project.canonicalPath()), json(project.verificationCommands()),
                    Timestamp.from(project.createdAt()), project.version());
            return project;
        } catch (DuplicateKeyException error) {
            Optional<Project> existing = findByCanonicalPath(project.canonicalPath());
            if (existing.isPresent()) {
                throw new DomainException("PROJECT_ALREADY_REGISTERED",
                        "Canonical Git repository is already registered as project " + existing.get().id()
                                + ": " + project.canonicalPath());
            }
            throw new DomainException("PROJECT_IDENTITY_CONFLICT", "Project identity is already registered: " + project.id());
        }
    }

    @Override
    public Optional<Project> findById(UUID id) {
        return jdbc.query("SELECT * FROM projects WHERE id=?", this::map, id.toString()).stream().findFirst();
    }

    @Override
    public Optional<Project> findByCanonicalPath(Path canonicalPath) {
        return jdbc.query("SELECT * FROM projects WHERE canonical_path_key=?", this::map,
                CanonicalPathIdentity.key(canonicalPath)).stream().findFirst();
    }

    @Override
    public List<Project> findAll() {
        return jdbc.query("SELECT * FROM projects ORDER BY created_at", this::map).stream()
                .sorted(Comparator.comparing(Project::createdAt)).toList();
    }

    private Project map(ResultSet rs, int row) throws SQLException {
        String owner = rs.getString("owner_id");
        return new Project(UUID.fromString(rs.getString("id")), rs.getString("name"),
                Path.of(rs.getString("canonical_path")), jsonList(rs.getString("verification_commands")),
                instant(rs.getTimestamp("created_at")), rs.getLong("version"),
                owner == null ? Project.LEGACY_OWNER_ID : UUID.fromString(owner));
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
