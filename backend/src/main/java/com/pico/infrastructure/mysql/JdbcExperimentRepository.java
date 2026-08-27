package com.pico.infrastructure.mysql;

import com.pico.experiment.domain.Experiment;
import com.pico.experiment.domain.ExperimentStatus;
import com.pico.port.ExperimentRepository;
import com.pico.shared.domain.DomainException;
import com.pico.verification.domain.VerificationResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("mysql")
public class JdbcExperimentRepository implements ExperimentRepository {
    private static final String INSERT = "INSERT INTO experiments (id,project_id,session_id,task,created_at,status,base_snapshot_id,result_snapshot_id,workspace_path,agent_summary,failure_reason,verification_passed,version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String UPDATE = "UPDATE experiments SET status=?, base_snapshot_id=?, result_snapshot_id=?, workspace_path=?, agent_summary=?, failure_reason=?, verification_passed=?, version=? WHERE id=? AND version=?";
    private final JdbcTemplate jdbc;

    public JdbcExperimentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Experiment save(Experiment experiment) {
        Boolean verified = experiment.verificationResult() == null ? null : experiment.verificationResult().passed();
        if (experiment.version() == 0) {
            try {
                jdbc.update(INSERT, experiment.id().toString(), experiment.projectId().toString(), experiment.sessionId().toString(),
                        experiment.task(), Timestamp.from(experiment.createdAt()), experiment.status().name(),
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
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM experiments WHERE session_id=? AND status IN ('RUNNING','AGENT_COMPLETED','VERIFYING')",
                Integer.class, sessionId.toString());
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
        String workspace = rs.getString("workspace_path");
        return Experiment.restore(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("project_id")),
                UUID.fromString(rs.getString("session_id")), rs.getString("task"), rs.getTimestamp("created_at").toInstant(),
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
