package com.offcanon.infrastructure.sqlite;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.port.ExperimentRepository;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.verification.domain.VerificationResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository

public class SqliteExperimentRepository implements ExperimentRepository {
    private static final String INSERT = "INSERT INTO experiments (id,project_id,session_id,continued_from_experiment_id,task,created_at,status,base_snapshot_id,result_snapshot_id,workspace_path,agent_summary,failure_reason,verification_passed,version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String UPDATE = "UPDATE experiments SET status=?, base_snapshot_id=?, result_snapshot_id=?, workspace_path=?, agent_summary=?, failure_reason=?, verification_passed=?, version=? WHERE id=? AND version=?";
    private final JdbcTemplate jdbc;

    public SqliteExperimentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Experiment save(Experiment experiment) {
        Boolean verified = experiment.verificationResult() == null ? null : experiment.verificationResult().passed();
        if (experiment.version() == 0) {
            try {
                jdbc.update(INSERT, experiment.id().toString(), experiment.projectId().toString(), experiment.sessionId().toString(),
                        nullable(experiment.continuedFromExperimentId()), experiment.task(),
                        SqliteValues.epochMicros(experiment.createdAt()), experiment.status().name(),
                        nullable(experiment.baseSnapshotId()), nullable(experiment.resultSnapshotId()), nullable(experiment.workspacePath()),
                        experiment.agentSummary(), experiment.failureReason(), verified, experiment.version());
            } catch (DuplicateKeyException error) {
                throw versionConflict(experiment);
            }
            return experiment;
        }
        int updated = jdbc.update(UPDATE, experiment.status().name(), nullable(experiment.baseSnapshotId()),
                nullable(experiment.resultSnapshotId()), nullable(experiment.workspacePath()), experiment.agentSummary(),
                experiment.failureReason(), verified, experiment.version(), experiment.id().toString(), experiment.version() - 1);
        if (updated != 1) throw versionConflict(experiment);
        return experiment;
    }

    @Override
    public Optional<Experiment> findById(UUID id) {
        return jdbc.query("SELECT * FROM experiments WHERE id=?", this::map, id.toString()).stream().findFirst();
    }

    @Override
    public List<Experiment> findByProjectId(UUID projectId) {
        return jdbc.query("SELECT * FROM experiments WHERE project_id=? ORDER BY created_at", this::map, projectId.toString());
    }

    @Override
    public List<Experiment> findBySessionId(UUID sessionId) {
        return jdbc.query("SELECT * FROM experiments WHERE session_id=? ORDER BY created_at", this::map, sessionId.toString());
    }

    @Override
    public boolean hasRunningExperiment(UUID sessionId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM experiments WHERE session_id=? AND status IN ('CREATED','SNAPSHOTTING','READY_TO_RUN','RUNNING','AGENT_COMPLETED','VERIFYING','PREPARING_PROMOTION','PROMOTING','RECOVERY_REQUIRED')",
                Integer.class, sessionId.toString());
        return count != null && count > 0;
    }

    @Override
    public boolean hasActiveExperimentForProject(UUID projectId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM experiments WHERE project_id=? AND status IN ('CREATED','SNAPSHOTTING','READY_TO_RUN','RUNNING','AGENT_COMPLETED','VERIFYING','VERIFIED','PREPARING_PROMOTION','PROMOTING','RECOVERY_REQUIRED')",
                Integer.class, projectId.toString());
        return count != null && count > 0;
    }

    @Override
    public boolean hasBlockingExperimentForProject(UUID projectId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM experiments WHERE project_id=? AND (status IN ('CREATED','SNAPSHOTTING','READY_TO_RUN','RUNNING','VERIFYING','PREPARING_PROMOTION','PROMOTING','RECOVERY_REQUIRED') OR (status='AGENT_COMPLETED' AND result_snapshot_id IS NULL))",
                Integer.class, projectId.toString());
        return count != null && count > 0;
    }

    private Experiment map(ResultSet rs, int row) throws SQLException {
        String failure = rs.getString("failure_reason");
        Boolean verified = rs.getObject("verification_passed", Boolean.class);
        VerificationResult result = verified == null ? null : verified
                ? VerificationResult.passed(List.of())
                : VerificationResult.failed(List.of(), failure == null ? "Verification failed" : failure);
        String base = rs.getString("base_snapshot_id");
        String resultSnapshot = rs.getString("result_snapshot_id");
        String continuedFrom = rs.getString("continued_from_experiment_id");
        String workspace = rs.getString("workspace_path");
        return Experiment.restore(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("project_id")),
                UUID.fromString(rs.getString("session_id")), continuedFrom == null ? null : UUID.fromString(continuedFrom),
                rs.getString("task"), SqliteValues.instant(rs, "created_at"),
                ExperimentStatus.valueOf(rs.getString("status")), base == null ? null : UUID.fromString(base),
                resultSnapshot == null ? null : UUID.fromString(resultSnapshot),
                workspace == null ? null : Path.of(workspace), rs.getString("agent_summary"), result, failure, rs.getLong("version"));
    }

    private String nullable(Object value) {
        return value == null ? null : value.toString();
    }

    private DomainException versionConflict(Experiment experiment) {
        return new DomainException("EXPERIMENT_VERSION_CONFLICT",
                "Experiment changed concurrently: " + experiment.id());
    }
}
