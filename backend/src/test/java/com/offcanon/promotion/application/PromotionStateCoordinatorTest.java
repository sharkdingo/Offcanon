package com.offcanon.promotion.application;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.infrastructure.memory.InMemoryExperimentRepository;
import com.offcanon.infrastructure.memory.InMemoryPromotionJournal;
import com.offcanon.promotion.domain.PromotionJournal;
import com.offcanon.promotion.domain.PromotionPhase;
import com.offcanon.verification.domain.VerificationResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PromotionStateCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-08-27T08:00:00Z");
    private static final String BASE = "base";
    private static final String CANDIDATE = "candidate";

    @Test
    void commitRepairsVerifiedExperimentWhenApplyingJournalOutranLifecycleMarker() {
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        InMemoryPromotionJournal journals = new InMemoryPromotionJournal();
        Experiment experiment = experiment(ExperimentStatus.VERIFIED);
        experiments.save(experiment);
        PromotionJournal applying = applying(journals, experiment);

        PromotionStateCoordinator coordinator = new PromotionStateCoordinator(experiments, journals,
                (org.springframework.transaction.PlatformTransactionManager) null);
        coordinator.commit(experiment, applying, CANDIDATE, NOW.plusSeconds(3));

        assertEquals(ExperimentStatus.PROMOTED, experiments.findById(experiment.id()).orElseThrow().status());
        assertEquals(PromotionPhase.COMMITTED,
                journals.findById(applying.promotionId()).orElseThrow().phase());
    }

    @Test
    void commitIsIdempotentWhenJournalWasCommittedBeforeLifecycleRepair() {
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        InMemoryPromotionJournal journals = new InMemoryPromotionJournal();
        Experiment experiment = experiment(ExperimentStatus.VERIFIED);
        experiments.save(experiment);
        PromotionJournal applying = applying(journals, experiment);
        PromotionJournal committed = journals.markCommitted(applying, CANDIDATE, NOW.plusSeconds(2));

        PromotionStateCoordinator coordinator = new PromotionStateCoordinator(experiments, journals,
                (org.springframework.transaction.PlatformTransactionManager) null);
        coordinator.commit(experiment, committed, CANDIDATE, NOW.plusSeconds(3));

        assertEquals(ExperimentStatus.PROMOTED, experiments.findById(experiment.id()).orElseThrow().status());
        assertEquals(PromotionPhase.COMMITTED,
                journals.findById(committed.promotionId()).orElseThrow().phase());
    }

    @Test
    void beginApplyingCompletesPartialLifecycleWhenJournalAlreadyApplying() {
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        InMemoryPromotionJournal journals = new InMemoryPromotionJournal();
        Experiment experiment = experiment(ExperimentStatus.PREPARING_PROMOTION);
        experiments.save(experiment);
        PromotionJournal applying = applying(journals, experiment);

        PromotionStateCoordinator coordinator = new PromotionStateCoordinator(experiments, journals,
                (org.springframework.transaction.PlatformTransactionManager) null);
        coordinator.beginApplying(experiment, applying, NOW.plusSeconds(3));

        assertEquals(ExperimentStatus.PROMOTING, experiments.findById(experiment.id()).orElseThrow().status());
    }

    private Experiment experiment(ExperimentStatus status) {
        return Experiment.restore(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "task", NOW,
                status, UUID.randomUUID(), UUID.randomUUID(), Path.of("C:/offcanon/workspace"),
                "done", VerificationResult.passed(List.of()), null, 0);
    }

    private PromotionJournal applying(InMemoryPromotionJournal journals, Experiment experiment) {
        PromotionJournal prepared = journals.create(PromotionJournal.create(experiment.id(), experiment.projectId(),
                BASE, CANDIDATE, Path.of("C:/offcanon/candidate"), "worker", NOW,
                NOW.plusSeconds(60)));
        return journals.markApplying(prepared, NOW.plusSeconds(1));
    }
}
