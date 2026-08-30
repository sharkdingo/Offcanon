package com.offcanon.infrastructure.sqlite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offcanon.memory.domain.TaskMemoryKind;
import com.offcanon.memory.domain.TaskMemoryOrigin;
import com.offcanon.memory.domain.TaskMemoryRevision;
import com.offcanon.memory.domain.TaskMemoryStatus;
import com.offcanon.memory.domain.TaskMemoryTrust;
import com.offcanon.port.TaskMemoryRepository;
import com.offcanon.shared.domain.DomainException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository

public class SqliteTaskMemoryRepository implements TaskMemoryRepository {
    private static final String INSERT = "INSERT INTO task_memory_revisions "
            + "(id,project_id,session_id,source_experiment_id,source_snapshot_id,source_fingerprint,memory_kind,content,"
            + "source_evidence_ids,origin,trust,status,supersedes_ids,created_at,sequence) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SqliteTaskMemoryRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public synchronized TaskMemoryRevision append(TaskMemoryRevision revision) {
        try {
            jdbc.update(INSERT, revision.id().toString(), revision.projectId().toString(),
                    revision.sessionId().toString(), revision.sourceExperimentId().toString(),
                    revision.sourceSnapshotId().toString(), revision.sourceFingerprint(), revision.kind().name(),
                    revision.content(), json(revision.sourceEvidenceIds()), revision.origin().name(),
                    revision.trust().name(), revision.status().name(), json(revision.supersedesIds()),
                    SqliteValues.epochMicros(revision.createdAt()), revision.sequence());
            return revision;
        } catch (DuplicateKeyException error) {
            Optional<TaskMemoryRevision> existing = findById(revision.id());
            if (existing.isPresent() && sameRevision(existing.orElseThrow(), revision)) return existing.orElseThrow();
            if (existing.isPresent()) {
                throw new DomainException("TASK_MEMORY_IDENTITY_CONFLICT",
                        "Task memory identity is already bound to different content: " + revision.id());
            }
            throw new DomainException("TASK_MEMORY_SEQUENCE_CONFLICT",
                    "Task memory sequence changed concurrently for session " + revision.sessionId()
                            + ": " + revision.sequence());
        }
    }

    private boolean sameRevision(TaskMemoryRevision left, TaskMemoryRevision right) {
        return left.id().equals(right.id())
                && left.projectId().equals(right.projectId())
                && left.sessionId().equals(right.sessionId())
                && left.sourceExperimentId().equals(right.sourceExperimentId())
                && left.sourceSnapshotId().equals(right.sourceSnapshotId())
                && left.sourceFingerprint().equals(right.sourceFingerprint())
                && left.kind() == right.kind()
                && left.content().equals(right.content())
                && left.sourceEvidenceIds().equals(right.sourceEvidenceIds())
                && left.origin() == right.origin()
                && left.trust() == right.trust()
                && left.status() == right.status()
                && left.supersedesIds().equals(right.supersedesIds())
                && left.createdAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                .equals(right.createdAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS))
                && left.sequence() == right.sequence();
    }

    @Override
    public Optional<TaskMemoryRevision> findById(UUID id) {
        return jdbc.query("SELECT * FROM task_memory_revisions WHERE id=?", this::map, id.toString())
                .stream().findFirst();
    }

    @Override
    public List<TaskMemoryRevision> findBySessionId(UUID sessionId) {
        return jdbc.query("SELECT * FROM task_memory_revisions WHERE session_id=? ORDER BY sequence,id",
                this::map, sessionId.toString());
    }

    @Override
    public synchronized long nextSequence(UUID sessionId) {
        Long current = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence),0) FROM task_memory_revisions WHERE session_id=?",
                Long.class, sessionId.toString());
        return (current == null ? 0 : current) + 1;
    }

    private TaskMemoryRevision map(ResultSet rs, int row) throws SQLException {
        return new TaskMemoryRevision(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("project_id")),
                UUID.fromString(rs.getString("session_id")),
                UUID.fromString(rs.getString("source_experiment_id")),
                UUID.fromString(rs.getString("source_snapshot_id")),
                rs.getString("source_fingerprint"),
                TaskMemoryKind.valueOf(rs.getString("memory_kind")),
                rs.getString("content"),
                uuidList(rs.getString("source_evidence_ids")),
                TaskMemoryOrigin.valueOf(rs.getString("origin")),
                TaskMemoryTrust.valueOf(rs.getString("trust")),
                TaskMemoryStatus.valueOf(rs.getString("status")),
                uuidList(rs.getString("supersedes_ids")),
                SqliteValues.instant(rs, "created_at"),
                rs.getLong("sequence"));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new DomainException("TASK_MEMORY_INVALID", "Unable to encode task memory provenance");
        }
    }

    private List<UUID> uuidList(String value) {
        try {
            List<String> ids = mapper.readValue(value == null ? "[]" : value, new TypeReference<>() { });
            return ids.stream().map(UUID::fromString).toList();
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new DomainException("TASK_MEMORY_INVALID", "Unable to decode task memory provenance");
        }
    }
}
