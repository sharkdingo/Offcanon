package com.offcanon.infrastructure.sqlite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offcanon.port.ProjectRepository;
import com.offcanon.project.domain.CanonicalPathIdentity;
import com.offcanon.project.domain.Project;
import com.offcanon.shared.domain.DomainException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository

public class SqliteProjectRepository implements ProjectRepository {
    private static final String INSERT = "INSERT INTO projects (id,owner_id,name,canonical_path,canonical_path_key,verification_commands,created_at,version) VALUES (?,?,?,?,?,?,?,?)";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SqliteProjectRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Project save(Project project) {
        try {
            jdbc.update(INSERT, project.id().toString(), project.ownerId().toString(), project.name(), project.canonicalPath().toString(),
                    CanonicalPathIdentity.key(project.canonicalPath()), json(project.verificationCommands()),
                    SqliteValues.epochMicros(project.createdAt()), project.version());
            return project;
        } catch (DuplicateKeyException error) {
            Optional<Project> byId = findById(project.id());
            if (byId.isPresent()) {
                if (sameProject(byId.get(), project)) {
                    return byId.get();
                }
                throw new DomainException("PROJECT_IDENTITY_CONFLICT",
                        "Project identity is already bound to different content: " + project.id());
            }
            Optional<Project> existing = findByCanonicalPath(project.canonicalPath());
            if (existing.isPresent()) {
                if (existing.get().id().equals(project.id())) {
                    if (sameProject(existing.get(), project)) {
                        return existing.get();
                    }
                    throw new DomainException("PROJECT_IDENTITY_CONFLICT",
                            "Project identity is already bound to different content: " + project.id());
                }
                throw new DomainException("PROJECT_ALREADY_REGISTERED",
                        "Canonical Git repository is already registered as project " + existing.get().id()
                                + ": " + project.canonicalPath());
            }
            throw new DomainException("PROJECT_IDENTITY_CONFLICT", "Project identity is already registered: " + project.id());
        }
    }

    @Override
    public Project update(Project project) {
        Optional<Project> current = findById(project.id());
        if (current.isEmpty()) {
            throw new DomainException("PROJECT_NOT_FOUND", "Project not found: " + project.id());
        }
        Project previous = current.get();
        if (!previous.ownerId().equals(project.ownerId())
                || !CanonicalPathIdentity.key(previous.canonicalPath())
                .equals(CanonicalPathIdentity.key(project.canonicalPath()))) {
            throw new DomainException("PROJECT_IDENTITY_CONFLICT",
                    "Project identity cannot be changed: " + project.id());
        }
        int updated = jdbc.update("UPDATE projects SET name=?, verification_commands=?, version=? WHERE id=? AND version=?",
                project.name(), json(project.verificationCommands()), project.version(),
                project.id().toString(), previous.version());
        if (updated == 1) return project;
        throw new DomainException("PROJECT_VERSION_CONFLICT",
                "Project was changed by another request: " + project.id());
    }

    private boolean sameProject(Project left, Project right) {
        return left.id().equals(right.id())
                && left.ownerId().equals(right.ownerId())
                && left.name().equals(right.name())
                && left.canonicalPath().toAbsolutePath().normalize()
                .equals(right.canonicalPath().toAbsolutePath().normalize())
                && left.verificationCommands().equals(right.verificationCommands())
                && left.createdAt().truncatedTo(ChronoUnit.MICROS)
                .equals(right.createdAt().truncatedTo(ChronoUnit.MICROS))
                && left.version() == right.version();
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
        return new Project(UUID.fromString(rs.getString("id")), rs.getString("name"),
                Path.of(rs.getString("canonical_path")), jsonList(rs.getString("verification_commands")),
                SqliteValues.instant(rs, "created_at"), rs.getLong("version"),
                UUID.fromString(rs.getString("owner_id")));
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
