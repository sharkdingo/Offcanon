package com.pico.infrastructure.mysql;

import com.pico.port.EvidenceRepository;
import com.pico.verification.domain.Evidence;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Repository
@Profile("mysql")
public class JdbcEvidenceRepository implements EvidenceRepository {
    private static final String INSERT = "INSERT INTO evidence (id,experiment_id,snapshot_id,kind,command,cwd,exit_code,stdout,stderr,started_at,completed_at,duration_millis,timed_out,trusted) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
            + "ON DUPLICATE KEY UPDATE stdout=VALUES(stdout), stderr=VALUES(stderr), completed_at=VALUES(completed_at)";
    private final JdbcTemplate jdbc;

    public JdbcEvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Evidence save(Evidence item) {
        jdbc.update(INSERT, item.id().toString(), item.experimentId().toString(), item.snapshotId().toString(), item.kind(),
                item.command(), item.cwd(), item.exitCode(), item.stdout(), item.stderr(), Timestamp.from(item.startedAt()),
                Timestamp.from(item.completedAt()), item.duration().toMillis(), item.timedOut(), item.trusted());
        return item;
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
                rs.getBoolean("timed_out"), rs.getBoolean("trusted"));
    }
}
