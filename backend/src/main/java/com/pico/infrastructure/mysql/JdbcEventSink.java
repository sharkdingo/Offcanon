package com.pico.infrastructure.mysql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pico.agent.domain.RunEvent;
import com.pico.port.EventSink;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Profile("mysql")
public class JdbcEventSink implements EventSink {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcEventSink(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public RunEvent publish(UUID experimentId, String type, Map<String, Object> payload) {
        // Lock the experiment row so sequence allocation remains monotonic across workers.
        jdbc.queryForObject("SELECT id FROM experiments WHERE id=? FOR UPDATE", String.class, experimentId.toString());
        Long last = jdbc.queryForObject("SELECT COALESCE(MAX(sequence), 0) FROM run_events WHERE experiment_id=?",
                Long.class, experimentId.toString());
        long sequence = (last == null ? 0 : last) + 1;
        Instant timestamp = Instant.now();
        RunEvent event = new RunEvent(UUID.randomUUID(), experimentId, sequence, type, timestamp, payload);
        jdbc.update("INSERT INTO run_events (event_id,experiment_id,sequence,type,event_timestamp,payload) VALUES (?,?,?,?,?,?)",
                event.eventId().toString(), experimentId.toString(), sequence, type, Timestamp.from(timestamp), json(payload));
        return event;
    }

    @Override
    public List<RunEvent> after(UUID experimentId, long sequence) {
        return jdbc.query("SELECT * FROM run_events WHERE experiment_id=? AND sequence>? ORDER BY sequence",
                this::map, experimentId.toString(), sequence);
    }

    private RunEvent map(ResultSet rs, int row) throws SQLException {
        return new RunEvent(UUID.fromString(rs.getString("event_id")),
                UUID.fromString(rs.getString("experiment_id")), rs.getLong("sequence"), rs.getString("type"),
                rs.getTimestamp("event_timestamp").toInstant(), payload(rs.getString("payload")));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to encode event payload", error);
        }
    }

    private Map<String, Object> payload(String value) {
        try {
            return mapper.readValue(value == null ? "{}" : value, new TypeReference<>() {
            });
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to decode event payload", error);
        }
    }
}
