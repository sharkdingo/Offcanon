package com.offcanon.promotion.application;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.infrastructure.memory.InMemoryEventSink;
import com.offcanon.infrastructure.memory.InMemoryExperimentRepository;
import com.offcanon.infrastructure.memory.InMemoryProjectRepository;
import com.offcanon.infrastructure.memory.InMemoryPromotionJournal;
import com.offcanon.infrastructure.memory.InMemoryPromotionLock;
import com.offcanon.port.SnapshotPort;
import com.offcanon.project.domain.Project;
import com.offcanon.promotion.domain.PromotionJournal;
import com.offcanon.promotion.domain.PromotionPhase;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.verification.domain.VerificationResult;
import com.offcanon.workspace.domain.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromotionRecoveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-27T08:00:00Z");
    private static final String BASE = "base-fingerprint";
    private static final String CANDIDATE = "candidate-fingerprint";

    @TempDir
    Path temp;

    private InMemoryPromotionJournal journals;
    private InMemoryExperimentRepository experiments;
    private InMemoryProjectRepository projects;
    private InMemoryEventSink events;

    @BeforeEach
    void setUp() {
        journals = new InMemoryPromotionJournal();
        experiments = new InMemoryExperimentRepository();
        projects = new InMemoryProjectRepository();
        events = new InMemoryEventSink();
    }

    @Test
    void expiredApplyingWithCandidateFingerprintCompletesPromotionAndCommitsJournal() {
        Fixture fixture = fixture(CANDIDATE, NOW.minusSeconds(1));

        fixture.service().reconcile(NOW);

        assertEquals(ExperimentStatus.PROMOTED, storedExperiment(fixture).status());
        PromotionJournal journal = storedJournal(fixture);
        assertEquals(PromotionPhase.COMMITTED, journal.phase());
        assertEquals(CANDIDATE, journal.resultingFingerprint());
        assertNull(journal.failureReason());
    }

    @Test
    void expiredApplyingWithBaseFingerprintRestoresVerifiedAndAbortsJournal() {
        Fixture fixture = fixture(BASE, NOW.minusSeconds(1));

        fixture.service().reconcile(NOW);

        assertEquals(ExperimentStatus.VERIFIED, storedExperiment(fixture).status());
        PromotionJournal journal = storedJournal(fixture);
        assertEquals(PromotionPhase.ABORTED, journal.phase());
        assertNull(journal.resultingFingerprint());
        assertEquals("Canonical still matches the promotion base; no apply was observed", journal.failureReason());
    }

    @Test
    void repeatedBaseRecoveryClosesApplyingJournalWhenExperimentIsAlreadyVerified() {
        Fixture fixture = fixture(BASE, NOW.minusSeconds(1), ExperimentStatus.VERIFIED);

        fixture.service().reconcile(NOW);

        assertEquals(ExperimentStatus.VERIFIED, storedExperiment(fixture).status());
        assertEquals(PromotionPhase.ABORTED, storedJournal(fixture).phase());
    }

    @Test
    void applyingWithBaseFingerprintCannotLeavePromotedExperimentAsIfItSucceeded() {
        Project project = projects.save(Project.create(java.util.UUID.randomUUID(), "demo", temp.resolve("canonical-promoted"), List.of("mvn test"), NOW));
        UUID experimentId = UUID.randomUUID();
        Experiment experiment = Experiment.restore(experimentId, project.id(), UUID.randomUUID(), "task", NOW.minusSeconds(60),
                ExperimentStatus.PROMOTED, UUID.randomUUID(), UUID.randomUUID(), temp.resolve("workspace-promoted"),
                "done", VerificationResult.passed(List.of()), null, 0);
        experiments.save(experiment);
        PromotionJournal journal = journals.create(PromotionJournal.create(experimentId, project.id(), BASE, CANDIDATE,
                temp.resolve("candidate-promoted"), "worker-1", NOW.minusSeconds(30), NOW.minusSeconds(1)));
        journal = journals.markApplying(journal, NOW.minusSeconds(10));
        FixedSnapshotPort snapshots = new FixedSnapshotPort(BASE);
        PromotionRecoveryService service = new PromotionRecoveryService(journals, experiments, projects, snapshots, events, new InMemoryPromotionLock());

        service.reconcile(NOW);

        assertEquals(ExperimentStatus.RECOVERY_REQUIRED, experiments.findById(experimentId).orElseThrow().status());
        assertEquals(PromotionPhase.RECOVERY_REQUIRED, journals.findById(journal.promotionId()).orElseThrow().phase());
    }

    @Test
    void expiredApplyingWithUnknownFingerprintRequiresManualRecovery() {
        Fixture fixture = fixture("unexpected-fingerprint", NOW.minusSeconds(1));

        fixture.service().reconcile(NOW);

        assertEquals(ExperimentStatus.RECOVERY_REQUIRED, storedExperiment(fixture).status());
        PromotionJournal journal = storedJournal(fixture);
        assertEquals(PromotionPhase.RECOVERY_REQUIRED, journal.phase());
        assertEquals("Canonical matches neither promotion base nor candidate", journal.failureReason());
    }

    @Test
    void lockLossAfterFingerprintReadDoesNotClassifyOrMutateRecoveryState() {
        Project project = projects.save(Project.create(UUID.randomUUID(), "lock-loss", temp.resolve("canonical-lock-loss"),
                List.of("mvn test"), NOW));
        UUID experimentId = UUID.randomUUID();
        Experiment experiment = Experiment.restore(experimentId, project.id(), UUID.randomUUID(), "task",
                NOW.minusSeconds(60), ExperimentStatus.PROMOTING, UUID.randomUUID(), UUID.randomUUID(),
                temp.resolve("workspace-lock-loss"), "done", VerificationResult.passed(List.of()), null, 0);
        experiments.save(experiment);
        PromotionJournal journal = journals.create(PromotionJournal.create(experimentId, project.id(), BASE, CANDIDATE,
                temp.resolve("candidate-lock-loss"), "worker-1", NOW.minusSeconds(30), NOW.minusSeconds(1)));
        journal = journals.markApplying(journal, NOW.minusSeconds(10));
        FixedSnapshotPort snapshots = new FixedSnapshotPort(CANDIDATE);
        LosingPromotionLock lock = new LosingPromotionLock(4);
        PromotionRecoveryService service = new PromotionRecoveryService(journals, experiments, projects, snapshots,
                events, lock);

        service.reconcile(NOW);

        assertEquals(1, snapshots.calls());
        assertEquals(ExperimentStatus.PROMOTING, experiments.findById(experimentId).orElseThrow().status());
        PromotionJournal after = journals.findById(journal.promotionId()).orElseThrow();
        assertEquals(PromotionPhase.APPLYING, after.phase());
        assertTrue(!"worker-1".equals(after.ownerId()));
        assertTrue(after.leaseUntil().isAfter(NOW));
    }

    @Test
    void repeatedAmbiguousRecoveryClosesJournalWithoutResavingSameExperimentVersion() {
        Fixture fixture = fixture("unexpected-fingerprint", NOW.minusSeconds(1),
                ExperimentStatus.RECOVERY_REQUIRED);

        fixture.service().reconcile(NOW);

        assertEquals(ExperimentStatus.RECOVERY_REQUIRED, storedExperiment(fixture).status());
        assertEquals(PromotionPhase.RECOVERY_REQUIRED, storedJournal(fixture).phase());
    }

    @Test
    void applyingJournalWithActiveLeaseIsNotProcessed() {
        Fixture fixture = fixture(CANDIDATE, NOW.plusSeconds(30));

        fixture.service().reconcile(NOW);

        assertEquals(ExperimentStatus.PROMOTING, storedExperiment(fixture).status());
        PromotionJournal journal = storedJournal(fixture);
        assertEquals(PromotionPhase.APPLYING, journal.phase());
        assertNull(journal.resultingFingerprint());
        assertNull(journal.failureReason());
        assertEquals(0, fixture.snapshots().calls());
        assertEquals(List.of(), events.after(fixture.experimentId(), 0));
    }

    @Test
    void startupAuditImmediatelyReportsActiveLeaseWithoutClaimingOrReadingCanonical() {
        Fixture fixture = fixture(CANDIDATE, NOW.plusSeconds(30));
        PromotionJournal before = storedJournal(fixture);

        fixture.service().auditOpenJournals(NOW);

        PromotionJournal after = storedJournal(fixture);
        assertEquals(before, after);
        assertEquals(ExperimentStatus.PROMOTING, storedExperiment(fixture).status());
        assertEquals(0, fixture.snapshots().calls());
        var auditEvents = events.after(fixture.experimentId(), 0);
        assertEquals(1, auditEvents.size());
        assertEquals("PROMOTION_RECOVERY_DEFERRED", auditEvents.get(0).type());
        assertEquals("ACTIVE_LEASE", auditEvents.get(0).payload().get("status"));
        assertEquals(true, auditEvents.get(0).payload().get("promotionBlocked"));
        assertTrue(journals.findUnresolvedByProject(before.projectId()).stream()
                .anyMatch(journal -> journal.promotionId().equals(before.promotionId())));
    }

    @Test
    void startupAuditImmediatelyReconcilesExpiredJournal() {
        Fixture fixture = fixture(CANDIDATE, NOW.minusSeconds(1));

        fixture.service().auditOpenJournals(NOW);

        assertEquals(ExperimentStatus.PROMOTED, storedExperiment(fixture).status());
        assertEquals(PromotionPhase.COMMITTED, storedJournal(fixture).phase());
        assertEquals(1, fixture.snapshots().calls());
    }

    @Test
    void expiredPreparedJournalWithExternalChangeBecomesStaleWithoutPromotion() {
        Fixture fixture = fixturePrepared("unexpected-fingerprint", NOW.minusSeconds(1));

        fixture.service().reconcile(NOW);

        assertEquals(ExperimentStatus.STALE, storedExperiment(fixture).status());
        assertEquals(PromotionPhase.ABORTED, storedJournal(fixture).phase());
    }

    @Test
    void manualReconcileCommitsRecoveryWhenCanonicalMatchesCandidate() {
        Fixture fixture = fixture("unexpected-fingerprint", NOW.minusSeconds(1));
        fixture.service().reconcile(NOW);
        fixture.snapshots().setCurrent(CANDIDATE);

        var outcome = fixture.service().reconcileRequired(fixture.experimentId());

        assertEquals("PROMOTED", outcome.experimentStatus());
        assertEquals("COMMITTED", outcome.journalPhase());
        assertEquals(CANDIDATE, outcome.fingerprint());
        assertEquals(ExperimentStatus.PROMOTED, storedExperiment(fixture).status());
        assertEquals(PromotionPhase.COMMITTED, storedJournal(fixture).phase());
        assertEquals(CANDIDATE, storedJournal(fixture).resultingFingerprint());
    }

    @Test
    void manualReconcileAbortsRecoveryWhenCanonicalMatchesBase() {
        Fixture fixture = fixture("unexpected-fingerprint", NOW.minusSeconds(1));
        fixture.service().reconcile(NOW);
        fixture.snapshots().setCurrent(BASE);

        var outcome = fixture.service().reconcileRequired(fixture.experimentId());

        assertEquals("VERIFIED", outcome.experimentStatus());
        assertEquals("ABORTED", outcome.journalPhase());
        assertEquals(BASE, outcome.fingerprint());
        assertEquals(ExperimentStatus.VERIFIED, storedExperiment(fixture).status());
        assertEquals(PromotionPhase.ABORTED, storedJournal(fixture).phase());
    }

    @ParameterizedTest
    @EnumSource(value = ExperimentStatus.class, names = {"VERIFIED", "PREPARING_PROMOTION", "PROMOTING"})
    void manualReconcileCommitsWhenRecoveryJournalOutranExperimentMarker(ExperimentStatus status) {
        Fixture fixture = fixture(CANDIDATE, NOW.plusSeconds(30), status);
        markJournalRecoveryRequired(fixture);

        var outcome = fixture.service().reconcileRequired(fixture.experimentId());

        assertEquals("PROMOTED", outcome.experimentStatus());
        assertEquals("COMMITTED", outcome.journalPhase());
        assertEquals(ExperimentStatus.PROMOTED, storedExperiment(fixture).status());
        assertEquals(PromotionPhase.COMMITTED, storedJournal(fixture).phase());
    }

    @ParameterizedTest
    @EnumSource(value = ExperimentStatus.class, names = {"VERIFIED", "PREPARING_PROMOTION", "PROMOTING"})
    void manualReconcileAbortsWhenRecoveryJournalOutranExperimentMarker(ExperimentStatus status) {
        Fixture fixture = fixture(BASE, NOW.plusSeconds(30), status);
        markJournalRecoveryRequired(fixture);

        var outcome = fixture.service().reconcileRequired(fixture.experimentId());

        assertEquals("VERIFIED", outcome.experimentStatus());
        assertEquals("ABORTED", outcome.journalPhase());
        assertEquals(ExperimentStatus.VERIFIED, storedExperiment(fixture).status());
        assertEquals(PromotionPhase.ABORTED, storedJournal(fixture).phase());
    }

    @Test
    void manualReconcileClosesJournalWhenExperimentCommitWasAlreadyPersisted() {
        Fixture fixture = fixture(CANDIDATE, NOW.plusSeconds(30), ExperimentStatus.PROMOTED);
        markJournalRecoveryRequired(fixture);
        long version = storedExperiment(fixture).version();

        fixture.service().reconcileRequired(fixture.experimentId());

        assertEquals(ExperimentStatus.PROMOTED, storedExperiment(fixture).status());
        assertEquals(version, storedExperiment(fixture).version());
        assertEquals(PromotionPhase.COMMITTED, storedJournal(fixture).phase());
    }

    @Test
    void manualReconcileRejectsPromotedExperimentWhenCanonicalIsAtBase() {
        Fixture fixture = fixture(BASE, NOW.plusSeconds(30), ExperimentStatus.PROMOTED);
        markJournalRecoveryRequired(fixture);
        PromotionJournal beforeJournal = storedJournal(fixture);

        DomainException error = assertThrows(DomainException.class,
                () -> fixture.service().reconcileRequired(fixture.experimentId()));

        assertEquals("PROMOTION_STATE_MISMATCH", error.code());
        assertEquals(ExperimentStatus.PROMOTED, storedExperiment(fixture).status());
        assertEquals(beforeJournal, storedJournal(fixture));
    }

    @Test
    void manualReconcileRejectsUnrelatedTerminalExperimentEvenWhenCandidateMatches() {
        Fixture fixture = fixture(CANDIDATE, NOW.plusSeconds(30), ExperimentStatus.FAILED);
        markJournalRecoveryRequired(fixture);
        PromotionJournal beforeJournal = storedJournal(fixture);

        DomainException error = assertThrows(DomainException.class,
                () -> fixture.service().reconcileRequired(fixture.experimentId()));

        assertEquals("PROMOTION_STATE_MISMATCH", error.code());
        assertEquals(ExperimentStatus.FAILED, storedExperiment(fixture).status());
        assertEquals(beforeJournal, storedJournal(fixture));
    }

    @Test
    void manualReconcileLeavesRecoveryBlockedForUnknownCanonicalFingerprint() {
        Fixture fixture = fixture("unexpected-fingerprint", NOW.minusSeconds(1));
        fixture.service().reconcile(NOW);
        Experiment beforeExperiment = storedExperiment(fixture);
        PromotionJournal beforeJournal = storedJournal(fixture);

        DomainException error = assertThrows(DomainException.class,
                () -> fixture.service().reconcileRequired(fixture.experimentId()));

        assertEquals("PROMOTION_RECOVERY_FINGERPRINT_MISMATCH", error.code());
        assertEquals(ExperimentStatus.RECOVERY_REQUIRED, storedExperiment(fixture).status());
        assertEquals(beforeExperiment.version(), storedExperiment(fixture).version());
        assertEquals(beforeJournal, storedJournal(fixture));
    }

    @Test
    void projectStatusExposesUnresolvedJournalEvenWhenExperimentMarkerIsStillVerified() {
        Fixture fixture = fixture(CANDIDATE, NOW.plusSeconds(30), ExperimentStatus.VERIFIED);

        PromotionRecoveryService.ProjectRecoveryStatus status =
                fixture.service().status(storedJournal(fixture).projectId());

        assertTrue(status.recoveryRequired());
        assertEquals(storedJournal(fixture).promotionId(), status.promotionId());
        assertEquals(fixture.experimentId(), status.experimentId());
        assertEquals(PromotionPhase.APPLYING.name(), status.journalPhase());
        assertEquals(1, status.unresolvedCount());
    }

    @Test
    void projectReconcileCanRecoverAnExpiredApplyingJournalBeforeExperimentMarkerCatchesUp() {
        Fixture fixture = fixture(CANDIDATE, NOW.minusSeconds(1), ExperimentStatus.VERIFIED);

        PromotionRecoveryService.ManualReconciliation outcome =
                fixture.service().reconcileProject(storedJournal(fixture).projectId());

        assertEquals("PROMOTED", outcome.experimentStatus());
        assertEquals("COMMITTED", outcome.journalPhase());
        assertEquals(ExperimentStatus.PROMOTED, storedExperiment(fixture).status());
        assertEquals(PromotionPhase.COMMITTED, storedJournal(fixture).phase());
    }

    @Test
    void projectReconcileRepairsRecoveryMarkerWhenJournalWasAlreadyCommitted() {
        Fixture fixture = fixture(CANDIDATE, NOW.plusSeconds(30), ExperimentStatus.RECOVERY_REQUIRED);
        PromotionJournal applying = storedJournal(fixture);
        journals.markCommitted(applying, CANDIDATE, NOW.plusSeconds(1));

        PromotionRecoveryService.ProjectRecoveryStatus status =
                fixture.service().status(applying.projectId());
        assertTrue(status.recoveryRequired());
        assertEquals(PromotionPhase.COMMITTED.name(), status.journalPhase());

        PromotionRecoveryService.ManualReconciliation outcome =
                fixture.service().reconcileProject(applying.projectId());

        assertEquals("PROMOTED", outcome.experimentStatus());
        assertEquals(ExperimentStatus.PROMOTED, storedExperiment(fixture).status());
    }

    private Fixture fixture(String currentFingerprint, Instant leaseUntil) {
        return fixture(currentFingerprint, leaseUntil, ExperimentStatus.PROMOTING);
    }

    private Fixture fixture(String currentFingerprint, Instant leaseUntil, ExperimentStatus status) {
        Project project = projects.save(Project.create(java.util.UUID.randomUUID(), "demo", temp.resolve("canonical"), List.of("mvn test"), NOW));
        UUID experimentId = UUID.randomUUID();
        Experiment experiment = Experiment.restore(experimentId, project.id(), UUID.randomUUID(), "task", NOW.minusSeconds(60),
                status, UUID.randomUUID(), UUID.randomUUID(), temp.resolve("workspace"),
                "done", VerificationResult.passed(List.of()),
                status == ExperimentStatus.RECOVERY_REQUIRED ? "already marked for recovery" : null, 0);
        experiments.save(experiment);

        PromotionJournal journal = journals.create(PromotionJournal.create(experimentId, project.id(), BASE, CANDIDATE,
                temp.resolve("candidate"), "worker-1", NOW.minusSeconds(30), leaseUntil));
        // Build the APPLYING journal while its worker lease is still valid;
        // reconciliation later observes the deliberately expired lease.
        journal = journals.markApplying(journal, NOW.minusSeconds(10));

        FixedSnapshotPort snapshots = new FixedSnapshotPort(currentFingerprint);
        PromotionRecoveryService service = new PromotionRecoveryService(journals, experiments, projects, snapshots, events, new InMemoryPromotionLock());
        return new Fixture(service, snapshots, experimentId, journal.promotionId());
    }

    private Fixture fixturePrepared(String currentFingerprint, Instant leaseUntil) {
        Project project = projects.save(Project.create(java.util.UUID.randomUUID(), "demo", temp.resolve("canonical-prepared"), List.of("mvn test"), NOW));
        UUID experimentId = UUID.randomUUID();
        Experiment experiment = Experiment.restore(experimentId, project.id(), UUID.randomUUID(), "task", NOW.minusSeconds(60),
                ExperimentStatus.PREPARING_PROMOTION, UUID.randomUUID(), UUID.randomUUID(), temp.resolve("workspace"),
                "done", VerificationResult.passed(List.of()), null, 0);
        experiments.save(experiment);
        PromotionJournal journal = journals.create(PromotionJournal.create(experimentId, project.id(), BASE, CANDIDATE,
                temp.resolve("candidate-prepared"), "worker-1", NOW.minusSeconds(30), leaseUntil));
        FixedSnapshotPort snapshots = new FixedSnapshotPort(currentFingerprint);
        PromotionRecoveryService service = new PromotionRecoveryService(journals, experiments, projects, snapshots, events, new InMemoryPromotionLock());
        return new Fixture(service, snapshots, experimentId, journal.promotionId());
    }

    private Experiment storedExperiment(Fixture fixture) {
        return experiments.findById(fixture.experimentId()).orElseThrow();
    }

    private PromotionJournal storedJournal(Fixture fixture) {
        return journals.findById(fixture.promotionId()).orElseThrow();
    }

    private void markJournalRecoveryRequired(Fixture fixture) {
        journals.markRecoveryRequired(storedJournal(fixture),
                "Experiment lifecycle marker could not be persisted", NOW);
    }

    private record Fixture(PromotionRecoveryService service,
                           FixedSnapshotPort snapshots,
                           UUID experimentId,
                           UUID promotionId) {
    }

    private static final class FixedSnapshotPort implements SnapshotPort {
        private final AtomicReference<String> currentFingerprint;
        private final AtomicInteger calls = new AtomicInteger();

        private FixedSnapshotPort(String currentFingerprint) {
            this.currentFingerprint = new AtomicReference<>(currentFingerprint);
        }

        @Override
        public Snapshot capture(Project project) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Snapshot captureWorkspace(Project project, Path workspace, String parentFingerprint) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String currentFingerprint(Project project) {
            calls.incrementAndGet();
            return currentFingerprint.get();
        }

        @Override
        public String fingerprintWorkspace(Project project, Path workspace, String parentFingerprint) {
            throw new UnsupportedOperationException();
        }

        private int calls() {
            return calls.get();
        }

        private void setCurrent(String fingerprint) {
            currentFingerprint.set(fingerprint);
        }
    }

    private static final class LosingPromotionLock implements com.offcanon.port.PromotionLockPort {
        private final int failAt;
        private final AtomicInteger assertions = new AtomicInteger();

        private LosingPromotionLock(int failAt) {
            this.failAt = failAt;
        }

        @Override
        public <T> T withProjectLock(UUID projectId, java.util.function.Supplier<T> action) {
            return action.get();
        }

        @Override
        public void assertHeld(UUID projectId) {
            if (assertions.incrementAndGet() >= failAt) {
                throw new DomainException("PROMOTION_LOCK_LOST", "injected recovery lock loss");
            }
        }
    }
}
