package com.pico.port;

import com.pico.promotion.domain.PromotionJournal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PromotionJournalPort {
    PromotionJournal create(PromotionJournal journal);
    Optional<PromotionJournal> findById(UUID promotionId);
    PromotionJournal markApplying(PromotionJournal journal, Instant now);
    PromotionJournal markCommitted(PromotionJournal journal, String resultingFingerprint, Instant now);
    PromotionJournal markAborted(PromotionJournal journal, String reason, Instant now);
    PromotionJournal markRecoveryRequired(PromotionJournal journal, String reason, Instant now);
    PromotionJournal resolveRecoveryCommitted(PromotionJournal journal, String resultingFingerprint, Instant now);
    PromotionJournal resolveRecoveryAborted(PromotionJournal journal, String reason, Instant now);
    Optional<PromotionJournal> tryClaimExpired(PromotionJournal journal, String newOwnerId, Instant now, Instant newLeaseUntil);
    List<PromotionJournal> findOpen();
    List<PromotionJournal> findExpiredOpen(Instant now);
    List<PromotionJournal> findUnresolvedByProject(UUID projectId);

    default PromotionJournal markApplying(PromotionJournal journal) {
        return markApplying(journal, Instant.now());
    }

    default PromotionJournal markCommitted(PromotionJournal journal, String resultingFingerprint) {
        return markCommitted(journal, resultingFingerprint, Instant.now());
    }

    default PromotionJournal markAborted(PromotionJournal journal, String reason) {
        return markAborted(journal, reason, Instant.now());
    }

    default PromotionJournal markRecoveryRequired(PromotionJournal journal, String reason) {
        return markRecoveryRequired(journal, reason, Instant.now());
    }
}
