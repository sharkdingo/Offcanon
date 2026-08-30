package com.offcanon.infrastructure.sqlite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offcanon.port.PromotionJournalPort;
import com.offcanon.promotion.domain.PromotionJournal;
import com.offcanon.promotion.domain.PromotionPhase;
import com.offcanon.shared.domain.DomainException;
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
import java.util.Map;

@Repository

public class SqlitePromotionJournal implements PromotionJournalPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SqlitePromotionJournal(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public PromotionJournal create(PromotionJournal journal) {
        jdbc.update("INSERT INTO promotion_journal (promotion_id,experiment_id,project_id,base_fingerprint,candidate_fingerprint,candidate_path,touched_files,preimage_hashes,postimage_hashes,phase,owner_id,lease_until,created_at,updated_at,resulting_fingerprint,failure_reason,version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                journal.promotionId().toString(), journal.experimentId().toString(), journal.projectId().toString(),
                journal.baseFingerprint(), journal.candidateFingerprint(), journal.candidatePath().toString(),
                json(journal.touchedFiles()), json(journal.preimageHashes()), json(journal.postimageHashes()),
                journal.phase().name(), journal.ownerId(), SqliteValues.epochMicros(journal.leaseUntil()),
                SqliteValues.epochMicros(journal.createdAt()), SqliteValues.epochMicros(journal.updatedAt()), journal.resultingFingerprint(),
                journal.failureReason(), journal.version());
        return journal;
    }

    @Override
    public Optional<PromotionJournal> findById(UUID promotionId) {
        return jdbc.query("SELECT * FROM promotion_journal WHERE promotion_id=?", this::map, promotionId.toString())
                .stream().findFirst();
    }

    @Override
    public List<PromotionJournal> findByExperimentId(UUID experimentId) {
        return jdbc.query("SELECT * FROM promotion_journal WHERE experiment_id=? ORDER BY created_at,promotion_id",
                this::map, experimentId.toString());
    }

    @Override
    @Transactional
    public PromotionJournal markApplying(PromotionJournal journal, Instant now) {
        return transition(journal, PromotionPhase.APPLYING, null, null, now);
    }

    @Override
    @Transactional
    public PromotionJournal markCommitted(PromotionJournal journal, String resultingFingerprint, Instant now) {
        return transition(journal, PromotionPhase.COMMITTED, resultingFingerprint, null, now);
    }

    @Override
    @Transactional
    public PromotionJournal markAborted(PromotionJournal journal, String reason, Instant now) {
        return transition(journal, PromotionPhase.ABORTED, null, reason, now);
    }

    @Override
    @Transactional
    public PromotionJournal markRecoveryRequired(PromotionJournal journal, String reason, Instant now) {
        return transition(journal, PromotionPhase.RECOVERY_REQUIRED, null, reason, now);
    }

    @Override
    @Transactional
    public PromotionJournal resolveRecoveryCommitted(PromotionJournal journal,
                                                      String resultingFingerprint,
                                                      Instant now) {
        return resolveRecovery(journal, PromotionPhase.COMMITTED, resultingFingerprint, null, now);
    }

    @Override
    @Transactional
    public PromotionJournal resolveRecoveryAborted(PromotionJournal journal, String reason, Instant now) {
        return resolveRecovery(journal, PromotionPhase.ABORTED, null, reason, now);
    }

    @Override
    @Transactional
    public Optional<PromotionJournal> tryClaimExpired(PromotionJournal expected,
                                                      String newOwnerId,
                                                      Instant now,
                                                      Instant newLeaseUntil) {
        expected.claimed(newOwnerId, now, newLeaseUntil);
        int changed = jdbc.update("UPDATE promotion_journal SET owner_id=?, lease_until=?, updated_at=?, version=version+1 WHERE promotion_id=? AND phase=? AND version=? AND owner_id=? AND lease_until<=?",
                newOwnerId, SqliteValues.epochMicros(newLeaseUntil), SqliteValues.epochMicros(now), expected.promotionId().toString(),
                expected.phase().name(), expected.version(), expected.ownerId(), SqliteValues.epochMicros(now));
        return changed == 1 ? findById(expected.promotionId()) : Optional.empty();
    }

    @Override
    public List<PromotionJournal> findExpiredOpen(Instant now) {
        return jdbc.query("SELECT * FROM promotion_journal WHERE phase IN ('PREPARED','APPLYING') AND lease_until<=? ORDER BY created_at,promotion_id",
                this::map, SqliteValues.epochMicros(now));
    }

    @Override
    public List<PromotionJournal> findOpen() {
        return jdbc.query("SELECT * FROM promotion_journal WHERE phase IN ('PREPARED','APPLYING') ORDER BY created_at,promotion_id",
                this::map);
    }

    @Override
    public List<PromotionJournal> findUnresolvedByProject(UUID projectId) {
        return jdbc.query("SELECT * FROM promotion_journal WHERE project_id=? AND phase NOT IN ('COMMITTED','ABORTED') ORDER BY created_at,promotion_id",
                this::map, projectId.toString());
    }

    private PromotionJournal transition(PromotionJournal expected, PromotionPhase next, String result, String reason, Instant now) {
        expected.transitioned(next, now, result, reason);
        int changed = jdbc.update("UPDATE promotion_journal SET phase=?, updated_at=?, resulting_fingerprint=?, failure_reason=?, version=version+1 WHERE promotion_id=? AND phase=? AND version=? AND owner_id=? AND lease_until>?",
                next.name(), SqliteValues.epochMicros(now), result, reason, expected.promotionId().toString(),
                expected.phase().name(), expected.version(), expected.ownerId(), SqliteValues.epochMicros(now));
        if (changed != 1) throw new DomainException("PROMOTION_JOURNAL_CONFLICT", "Promotion journal changed concurrently");
        return findById(expected.promotionId()).orElseThrow(() -> new DomainException("PROMOTION_JOURNAL_MISSING", "Promotion journal disappeared"));
    }

    private PromotionJournal resolveRecovery(PromotionJournal expected,
                                              PromotionPhase next,
                                              String result,
                                              String reason,
                                              Instant now) {
        expected.reconciled(next, now, result, reason);
        int changed = jdbc.update("UPDATE promotion_journal SET phase=?, updated_at=?, resulting_fingerprint=?, failure_reason=?, version=version+1 WHERE promotion_id=? AND phase='RECOVERY_REQUIRED' AND version=? AND owner_id=?",
                next.name(), SqliteValues.epochMicros(now), result, reason, expected.promotionId().toString(),
                expected.version(), expected.ownerId());
        if (changed != 1) throw new DomainException("PROMOTION_JOURNAL_CONFLICT", "Recovery journal changed concurrently");
        return findById(expected.promotionId()).orElseThrow(() -> new DomainException("PROMOTION_JOURNAL_MISSING", "Promotion journal disappeared"));
    }

    private PromotionJournal map(ResultSet rs, int row) throws SQLException {
        return new PromotionJournal(UUID.fromString(rs.getString("promotion_id")),
                UUID.fromString(rs.getString("experiment_id")), UUID.fromString(rs.getString("project_id")),
                rs.getString("base_fingerprint"), rs.getString("candidate_fingerprint"),
                Path.of(rs.getString("candidate_path")), stringList(rs.getString("touched_files")),
                stringMap(rs.getString("preimage_hashes")), stringMap(rs.getString("postimage_hashes")),
                PromotionPhase.valueOf(rs.getString("phase")),
                rs.getString("owner_id"), SqliteValues.instant(rs, "lease_until"),
                SqliteValues.instant(rs, "created_at"), SqliteValues.instant(rs, "updated_at"),
                rs.getString("resulting_fingerprint"), rs.getString("failure_reason"), rs.getLong("version"));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new DomainException("PROMOTION_JOURNAL_INVALID", "Unable to encode promotion plan");
        }
    }

    private List<String> stringList(String value) {
        try {
            return mapper.readValue(value == null ? "[]" : value, new TypeReference<>() {
            });
        } catch (JsonProcessingException error) {
            throw new DomainException("PROMOTION_JOURNAL_INVALID", "Unable to decode touched files");
        }
    }

    private Map<String, String> stringMap(String value) {
        try {
            return mapper.readValue(value == null ? "{}" : value, new TypeReference<>() {
            });
        } catch (JsonProcessingException error) {
            throw new DomainException("PROMOTION_JOURNAL_INVALID", "Unable to decode preimage hashes");
        }
    }
}
