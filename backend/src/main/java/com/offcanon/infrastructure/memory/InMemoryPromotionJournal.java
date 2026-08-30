package com.offcanon.infrastructure.memory;

import com.offcanon.port.PromotionJournalPort;
import com.offcanon.promotion.domain.PromotionJournal;
import com.offcanon.promotion.domain.PromotionPhase;
import com.offcanon.shared.domain.DomainException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("!mysql")
public class InMemoryPromotionJournal implements PromotionJournalPort {
    private final ConcurrentHashMap<UUID, PromotionJournal> journals = new ConcurrentHashMap<>();

    @Override
    public PromotionJournal create(PromotionJournal journal) {
        if (journals.putIfAbsent(journal.promotionId(), journal) != null) {
            throw new DomainException("PROMOTION_JOURNAL_CONFLICT", "Promotion journal already exists");
        }
        return journal;
    }

    @Override
    public Optional<PromotionJournal> findById(UUID promotionId) {
        return Optional.ofNullable(journals.get(promotionId));
    }

    @Override
    public List<PromotionJournal> findByExperimentId(UUID experimentId) {
        return journals.values().stream()
                .filter(journal -> journal.experimentId().equals(experimentId))
                .sorted(Comparator.comparing(PromotionJournal::createdAt)
                        .thenComparing(PromotionJournal::promotionId))
                .toList();
    }

    @Override
    public PromotionJournal markApplying(PromotionJournal journal, Instant now) {
        return transition(journal, PromotionPhase.APPLYING, null, null, now);
    }

    @Override
    public PromotionJournal markCommitted(PromotionJournal journal, String resultingFingerprint, Instant now) {
        return transition(journal, PromotionPhase.COMMITTED, resultingFingerprint, null, now);
    }

    @Override
    public PromotionJournal markAborted(PromotionJournal journal, String reason, Instant now) {
        return transition(journal, PromotionPhase.ABORTED, null, reason, now);
    }

    @Override
    public PromotionJournal markRecoveryRequired(PromotionJournal journal, String reason, Instant now) {
        return transition(journal, PromotionPhase.RECOVERY_REQUIRED, null, reason, now);
    }

    @Override
    public PromotionJournal resolveRecoveryCommitted(PromotionJournal journal,
                                                      String resultingFingerprint,
                                                      Instant now) {
        return resolveRecovery(journal, PromotionPhase.COMMITTED, resultingFingerprint, null, now);
    }

    @Override
    public PromotionJournal resolveRecoveryAborted(PromotionJournal journal, String reason, Instant now) {
        return resolveRecovery(journal, PromotionPhase.ABORTED, null, reason, now);
    }

    @Override
    public Optional<PromotionJournal> tryClaimExpired(PromotionJournal expected,
                                                      String newOwnerId,
                                                      Instant now,
                                                      Instant newLeaseUntil) {
        PromotionJournal claimed;
        try {
            claimed = expected.claimed(newOwnerId, now, newLeaseUntil);
        } catch (IllegalStateException error) {
            return Optional.empty();
        }
        return journals.replace(expected.promotionId(), expected, claimed)
                ? Optional.of(claimed)
                : Optional.empty();
    }

    @Override
    public List<PromotionJournal> findExpiredOpen(Instant now) {
        return findOpen().stream()
                .filter(journal -> !journal.leaseUntil().isAfter(now))
                .toList();
    }

    @Override
    public List<PromotionJournal> findOpen() {
        return journals.values().stream()
                .filter(journal -> journal.phase() == PromotionPhase.PREPARED
                        || journal.phase() == PromotionPhase.APPLYING)
                .sorted(Comparator.comparing(PromotionJournal::createdAt)
                        .thenComparing(PromotionJournal::promotionId))
                .toList();
    }

    @Override
    public List<PromotionJournal> findUnresolvedByProject(UUID projectId) {
        return journals.values().stream()
                .filter(journal -> journal.projectId().equals(projectId))
                .filter(journal -> journal.phase() != PromotionPhase.COMMITTED
                        && journal.phase() != PromotionPhase.ABORTED)
                .sorted(Comparator.comparing(PromotionJournal::createdAt)
                        .thenComparing(PromotionJournal::promotionId))
                .toList();
    }

    private PromotionJournal transition(PromotionJournal expected, PromotionPhase phase, String result, String reason, Instant now) {
        if (!expected.leaseUntil().isAfter(now)) {
            throw new DomainException("PROMOTION_JOURNAL_EXPIRED", "Promotion journal lease has expired");
        }
        PromotionJournal updated = expected.transitioned(phase, now, result, reason);
        boolean replaced = journals.replace(expected.promotionId(), expected, updated);
        if (!replaced) throw new DomainException("PROMOTION_JOURNAL_CONFLICT", "Promotion journal changed concurrently");
        return updated;
    }

    private PromotionJournal resolveRecovery(PromotionJournal expected,
                                              PromotionPhase phase,
                                              String result,
                                              String reason,
                                              Instant now) {
        PromotionJournal updated = expected.reconciled(phase, now, result, reason);
        boolean replaced = journals.replace(expected.promotionId(), expected, updated);
        if (!replaced) throw new DomainException("PROMOTION_JOURNAL_CONFLICT", "Promotion journal changed concurrently");
        return updated;
    }
}
