package com.offcanon.infrastructure.mysql;

import com.offcanon.port.EvidenceRepository;
import com.offcanon.verification.domain.Evidence;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.time.temporal.ChronoUnit;
import com.offcanon.shared.domain.DomainException;

@Repository
@Profile("mysql")
public class JdbcEvidenceRepository implements EvidenceRepository {
    private static final String INSERT = "INSERT INTO evidence (id,experiment_id,snapshot_id,kind,command,cwd,exit_code,stdout,stderr,started_at,completed_at,duration_millis,timed_out,trusted,environment_profile,cancelled) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private final JdbcTemplate jdbc;

    public JdbcEvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Evidence save(Evidence item) {
        try {
            jdbc.update(INSERT, item.id().toString(), item.experimentId().toString(), item.snapshotId().toString(), item.kind(),
                    item.command(), item.cwd(), item.exitCode(), item.stdout(), item.stderr(), Timestamp.from(item.startedAt()),
                    Timestamp.from(item.completedAt()), item.duration().toMillis(), item.timedOut(), item.trusted(),
                    item.environmentProfile(), item.cancelled());
            return item;
        } catch (DuplicateKeyException error) {
            Evidence stored = findById(item.id());
            if (stored != null && sameEvidence(stored, item)) return stored;
            throw new DomainException("EVIDENCE_IDENTITY_CONFLICT",
                    "Evidence identity already belongs to different content: " + item.id());
        }
    }

    private Evidence findById(UUID id) {
        return jdbc.query("SELECT * FROM evidence WHERE id=?", this::map, id.toString()).stream()
                .findFirst().orElse(null);
    }

    private boolean sameEvidence(Evidence left, Evidence right) {
        return left.id().equals(right.id())
                && left.experimentId().equals(right.experimentId())
                && left.snapshotId().equals(right.snapshotId())
                && left.kind().equals(right.kind())
                && left.command().equals(right.command())
                && left.cwd().equals(right.cwd())
                && left.exitCode() == right.exitCode()
                && left.stdout().equals(right.stdout())
                && left.stderr().equals(right.stderr())
                && left.startedAt().truncatedTo(ChronoUnit.MICROS).equals(right.startedAt().truncatedTo(ChronoUnit.MICROS))
                && left.completedAt().truncatedTo(ChronoUnit.MICROS).equals(right.completedAt().truncatedTo(ChronoUnit.MICROS))
                && left.duration().toMillis() == right.duration().toMillis()
                && left.timedOut() == right.timedOut()
                && left.trusted() == right.trusted()
                && left.environmentProfile().equals(right.environmentProfile())
                && left.cancelled() == right.cancelled();
    }

    @Override
    public List<Evidence> findByExperimentId(UUID experimentId) {
        return jdbc.query("SELECT * FROM evidence WHERE experiment_id=? ORDER BY started_at", this::map, experimentId.toString());
    }

    private Evidence map(ResultSet rs, int row) throws SQLException {
        return new Evidence(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("experiment_id")),
                UUID.fromString(rs.getString("snapshot_id")), rs.getString("kind"), rs.getString("command"), rs.getString("cwd"),
                rs.getInt("exit_code"), rs.getString("stdout"), rs.getString("stderr"), rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at").toInstant(), Duration.ofMillis(rs.getLong("duration_millis")),
                rs.getBoolean("timed_out"), rs.getBoolean("trusted"), rs.getString("environment_profile"),
                rs.getBoolean("cancelled"));
    }
}
