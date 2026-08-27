package com.offcanon.promotion.domain;

import com.offcanon.infrastructure.memory.InMemoryPromotionJournal;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromotionJournalTest {
    private static final Instant NOW = Instant.parse("2026-08-27T08:00:00Z");

    @Test
    void onlyAllowedPhaseTransitionsAreAccepted() {
        PromotionJournal journal = journal(NOW.plusSeconds(600));
        InMemoryPromotionJournal store = new InMemoryPromotionJournal();
        journal = store.create(journal);
        PromotionJournal prepared = journal;
        assertThrows(IllegalStateException.class, () -> store.markCommitted(prepared, "candidate", NOW.plusSeconds(1)));
        journal = store.markApplying(journal, NOW.plusSeconds(1));
        journal = store.markCommitted(journal, "candidate", NOW.plusSeconds(2));
        assertEquals(PromotionPhase.COMMITTED, journal.phase());
        assertEquals(java.util.List.of("service.txt"), journal.touchedFiles());
        assertEquals("before-hash", journal.preimageHashes().get("service.txt"));
        PromotionJournal terminal = journal;
        assertThrows(IllegalStateException.class, () -> store.markAborted(terminal, "late", NOW.plusSeconds(3)));
    }

    @Test
    void expiredJournalCanBeClaimedOnlyOnce() {
        InMemoryPromotionJournal store = new InMemoryPromotionJournal();
        PromotionJournal journal = store.create(journal(NOW.minusSeconds(1)));
        var claimed = store.tryClaimExpired(journal, "recovery-a", NOW, NOW.plusSeconds(60));
        assertTrue(claimed.isPresent());
        assertEquals("recovery-a", claimed.orElseThrow().ownerId());
        assertFalse(store.tryClaimExpired(journal, "recovery-b", NOW, NOW.plusSeconds(60)).isPresent());
    }

    @Test
    void expiredJournalCannotBeTransitionedUntilARecoveryWorkerClaimsIt() {
        InMemoryPromotionJournal store = new InMemoryPromotionJournal();
        PromotionJournal journal = store.create(journal(NOW.minusSeconds(1)));

        assertThrows(com.offcanon.shared.domain.DomainException.class,
                () -> store.markApplying(journal, NOW));
        PromotionJournal claimed = store.tryClaimExpired(journal, "recovery", NOW, NOW.plusSeconds(60)).orElseThrow();
        PromotionJournal applying = store.markApplying(claimed, NOW.plusSeconds(1));

        assertEquals(PromotionPhase.APPLYING, applying.phase());
    }

    @Test
    void manuallyReconcilesOnlyARecoveryJournalWithoutReusingItsExpiredLease() {
        InMemoryPromotionJournal store = new InMemoryPromotionJournal();
        PromotionJournal prepared = store.create(journal(NOW.plusSeconds(60)));
        PromotionJournal recovery = store.markRecoveryRequired(prepared, "inspect canonical", NOW.plusSeconds(1));

        assertThrows(IllegalStateException.class,
                () -> store.markCommitted(recovery, "candidate", NOW.plusSeconds(2)));
        PromotionJournal committed = store.resolveRecoveryCommitted(recovery, "candidate", NOW.plusSeconds(120));

        assertEquals(PromotionPhase.COMMITTED, committed.phase());
        assertEquals("candidate", committed.resultingFingerprint());

        PromotionJournal second = store.create(journal(NOW.plusSeconds(60)));
        PromotionJournal secondRecovery = store.markRecoveryRequired(second, "inspect canonical", NOW.plusSeconds(1));
        PromotionJournal aborted = store.resolveRecoveryAborted(secondRecovery, "canonical is base", NOW.plusSeconds(120));
        assertEquals(PromotionPhase.ABORTED, aborted.phase());
        assertEquals("canonical is base", aborted.failureReason());
    }

    private PromotionJournal journal(Instant leaseUntil) {
        UUID experiment = UUID.randomUUID();
        return PromotionJournal.create(experiment, UUID.randomUUID(), "base", "candidate",
                Path.of("C:/offcanon/candidate"), java.util.List.of("service.txt"),
                java.util.Map.of("service.txt", "before-hash"), java.util.Map.of("service.txt", "after-hash"),
                "worker", NOW.minusSeconds(30), leaseUntil);
    }
}
