package com.offcanon.promotion.application;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.infrastructure.memory.InMemoryEventSink;
import com.offcanon.infrastructure.memory.InMemoryExperimentRepository;
import com.offcanon.infrastructure.memory.InMemoryProjectRepository;
import com.offcanon.infrastructure.memory.InMemoryPromotionLock;
import com.offcanon.infrastructure.memory.InMemoryPromotionJournal;
import com.offcanon.promotion.domain.PromotionJournal;
import com.offcanon.promotion.domain.PromotionPhase;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.infrastructure.memory.InMemorySnapshotRepository;
import com.offcanon.port.SnapshotPort;
import com.offcanon.project.domain.Project;
import com.offcanon.verification.domain.VerificationResult;
import com.offcanon.workspace.domain.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PromotionStaleApplicationServiceTest {
    @TempDir
    Path temp;

    @Test
    void conflictingCanonicalMarksExperimentStaleWithoutWritingCanonical() throws Exception {
        Fixture fixture = fixture("changed-canonical");

        PromotionStaleApplicationService.StaleConfirmation outcome = fixture.service.confirm(fixture.experiment.id());

        assertTrue(outcome.markedStale());
        assertEquals("STALE", outcome.status());
        assertEquals("changed-canonical", outcome.currentFingerprint());
        assertEquals(ExperimentStatus.STALE,
                fixture.experiments.findById(fixture.experiment.id()).orElseThrow().status());
        assertEquals("external work\n", Files.readString(fixture.canonicalFile));
    }

    @Test
    void canonicalRestoredToBaseKeepsExperimentVerifiedAndReturnsExplicitResult() throws Exception {
        Fixture fixture = fixture("base-fingerprint");

        PromotionStaleApplicationService.StaleConfirmation outcome = fixture.service.confirm(fixture.experiment.id());

        assertFalse(outcome.markedStale());
        assertEquals("CANONICAL_MATCHES_BASE", outcome.status());
        assertEquals("base-fingerprint", outcome.currentFingerprint());
        assertEquals(ExperimentStatus.VERIFIED,
                fixture.experiments.findById(fixture.experiment.id()).orElseThrow().status());
        assertEquals("external work\n", Files.readString(fixture.canonicalFile));
    }

    @Test
    void unresolvedProjectJournalRejectsStaleConfirmation() throws Exception {
        Fixture fixture = fixture("changed-canonical");
        InMemoryPromotionJournal journals = new InMemoryPromotionJournal();
        journals.create(PromotionJournal.create(fixture.experiment.id(), fixture.experiment.projectId(),
                "base-fingerprint", "candidate-fingerprint", temp.resolve("candidate"),
                "worker", Instant.now(), Instant.now().plusSeconds(60)));
        PromotionStaleApplicationService guarded = new PromotionStaleApplicationService(
                fixture.experiments, fixture.projects, fixture.snapshots,
                new FixedFingerprintSnapshotPort("changed-canonical"), new InMemoryEventSink(),
                new InMemoryPromotionLock(), journals);

        DomainException error = assertThrows(DomainException.class,
                () -> guarded.confirm(fixture.experiment.id()));

        assertEquals("PROMOTION_RECOVERY_PENDING", error.code());
        assertEquals(ExperimentStatus.VERIFIED,
                fixture.experiments.findById(fixture.experiment.id()).orElseThrow().status());
        assertEquals(PromotionPhase.PREPARED,
                journals.findUnresolvedByProject(fixture.experiment.projectId()).getFirst().phase());
    }

    @Test
    void refusesToMarkAnOlderResultStaleWhileItsSessionHasAQueuedSuccessor() throws Exception {
        Fixture fixture = fixture("changed-canonical");
        Experiment successor = Experiment.continueFrom(fixture.experiment.projectId(),
                fixture.experiment.sessionId(), fixture.experiment.id(), "continue the task", Instant.now());
        fixture.experiments.save(successor);

        PromotionStaleApplicationService.StaleConfirmation outcome = fixture.service.confirm(fixture.experiment.id());

        assertFalse(outcome.markedStale());
        assertEquals("SESSION_ALREADY_RUNNING", outcome.status());
        assertEquals(ExperimentStatus.VERIFIED,
                fixture.experiments.findById(fixture.experiment.id()).orElseThrow().status());
        assertEquals("external work\n", Files.readString(fixture.canonicalFile));
    }

    private Fixture fixture(String currentFingerprint) throws Exception {
        Path canonical = temp.resolve(UUID.randomUUID().toString());
        Files.createDirectories(canonical);
        Path canonicalFile = canonical.resolve("service.txt");
        Files.writeString(canonicalFile, "external work\n");

        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        InMemorySnapshotRepository snapshots = new InMemorySnapshotRepository();
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        Project project = projects.save(Project.create(java.util.UUID.randomUUID(), "demo", canonical, List.of(), Instant.now()));
        UUID baseSnapshotId = UUID.randomUUID();
        snapshots.save(new Snapshot(baseSnapshotId, project.id(), "base-fingerprint", temp.resolve("base"),
                Instant.now(), List.of("service.txt"), List.of()));
        Experiment experiment = Experiment.restore(UUID.randomUUID(), project.id(), UUID.randomUUID(), "change service",
                Instant.now(), ExperimentStatus.VERIFIED, baseSnapshotId, UUID.randomUUID(), temp.resolve("workspace"),
                "done", VerificationResult.passed(List.of()), null, 0);
        experiments.save(experiment);

        PromotionStaleApplicationService service = new PromotionStaleApplicationService(experiments, projects, snapshots,
                new FixedFingerprintSnapshotPort(currentFingerprint), new InMemoryEventSink(), new InMemoryPromotionLock(),
                new InMemoryPromotionJournal());
        return new Fixture(canonicalFile, experiment, experiments, service, projects, snapshots);
    }

    private record Fixture(Path canonicalFile,
                           Experiment experiment,
                           InMemoryExperimentRepository experiments,
                           PromotionStaleApplicationService service,
                           InMemoryProjectRepository projects,
                           InMemorySnapshotRepository snapshots) {
    }

    private record FixedFingerprintSnapshotPort(String fingerprint) implements SnapshotPort {
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
            return fingerprint;
        }

        @Override
        public String fingerprintWorkspace(Project project, Path workspace, String parentFingerprint) {
            throw new UnsupportedOperationException();
        }
    }
}
