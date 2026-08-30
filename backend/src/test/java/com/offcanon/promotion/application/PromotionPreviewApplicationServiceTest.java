package com.offcanon.promotion.application;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.infrastructure.memory.InMemoryExperimentRepository;
import com.offcanon.infrastructure.memory.InMemoryProjectRepository;
import com.offcanon.infrastructure.memory.InMemoryPromotionJournal;
import com.offcanon.infrastructure.memory.InMemorySnapshotRepository;
import com.offcanon.port.SnapshotPort;
import com.offcanon.project.domain.Project;
import com.offcanon.promotion.domain.PromotionJournal;
import com.offcanon.verification.domain.VerificationResult;
import com.offcanon.workspace.domain.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromotionPreviewApplicationServiceTest {
    @TempDir
    Path temp;

    @Test
    void exposesTrustedVerifiedCandidateWithoutMutatingExperiment() {
        Fixture fixture = fixture("base", "base", "candidate", ExperimentStatus.VERIFIED,
                VerificationResult.passed(List.of()), null);

        var preview = fixture.service.preview(fixture.experiment.id());

        assertEquals("base", preview.baseFingerprint());
        assertEquals("base", preview.currentFingerprint());
        assertEquals("candidate", preview.finalCandidateFingerprint());
        assertEquals("PASSED", preview.verificationStatus());
        assertTrue(preview.trustedVerification());
        assertFalse(preview.conflict());
        assertTrue(preview.promotable());
        assertFalse(preview.recoveryRequired());
        assertNull(preview.recoveryJournalPhase());
        assertNull(preview.recoveryPromotionId());
        assertNull(preview.blockingReason());
        assertEquals(ExperimentStatus.VERIFIED,
                fixture.experiments.findById(fixture.experiment.id()).orElseThrow().status());
    }

    @Test
    void reportsCanonicalConflictAndExistingStaleReason() {
        Fixture fixture = fixture("base", "current", "candidate", ExperimentStatus.STALE,
                VerificationResult.passed(List.of()), "Canonical changed during review");

        var preview = fixture.service.preview(fixture.experiment.id());

        assertTrue(preview.conflict());
        assertFalse(preview.promotable());
        assertEquals("Canonical changed during review", preview.blockingReason());
    }

    @Test
    void reportsUnsealedUnverifiedExperimentAsNotTrusted() {
        Fixture fixture = fixture("base", "base", null, ExperimentStatus.READY_TO_RUN, null, null);

        var preview = fixture.service.preview(fixture.experiment.id());

        assertEquals("NOT_RUN", preview.verificationStatus());
        assertFalse(preview.trustedVerification());
        assertFalse(preview.promotable());
        assertEquals("Final candidate has not been sealed", preview.blockingReason());
    }

    @Test
    void unresolvedProjectPromotionDisablesAnOtherwisePromotablePreview() {
        Fixture fixture = fixture("base", "base", "candidate", ExperimentStatus.VERIFIED,
                VerificationResult.passed(List.of()), null);
        fixture.journals.create(PromotionJournal.create(fixture.experiment.id(), fixture.experiment.projectId(),
                "base", "candidate", temp.resolve("candidate"), "worker", Instant.now(), Instant.now().plusSeconds(60)));

        var preview = fixture.service.preview(fixture.experiment.id());

        assertFalse(preview.promotable());
        assertEquals("An earlier promotion requires recovery before this project can be changed", preview.blockingReason());
        assertTrue(preview.recoveryRequired());
        assertEquals("PREPARED", preview.recoveryJournalPhase());
        assertTrue(preview.recoveryPromotionId() != null);
    }

    @Test
    void promotedCandidateMatchingCurrentIsAnOutcomeRatherThanAConflict() {
        Fixture fixture = fixture("base", "candidate", "candidate", ExperimentStatus.PROMOTED,
                VerificationResult.passed(List.of()), null);

        var preview = fixture.service.preview(fixture.experiment.id());

        assertFalse(preview.conflict());
        assertFalse(preview.promotable());
        assertEquals("Candidate is already canonical", preview.blockingReason());
    }

    private Fixture fixture(String baseFingerprint,
                            String currentFingerprint,
                            String candidateFingerprint,
                            ExperimentStatus status,
                            VerificationResult verification,
                            String failureReason) {
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        InMemorySnapshotRepository snapshots = new InMemorySnapshotRepository();
        Project project = projects.save(Project.create(java.util.UUID.randomUUID(), "preview", temp.resolve("canonical"), List.of("mvn test"), Instant.now()));
        Snapshot base = snapshots.save(snapshot(project.id(), baseFingerprint, "base"));
        Snapshot candidate = candidateFingerprint == null ? null
                : snapshots.save(snapshot(project.id(), candidateFingerprint, "candidate"));
        Experiment experiment = Experiment.restore(UUID.randomUUID(), project.id(), UUID.randomUUID(), "preview",
                Instant.now(), status, base.id(), candidate == null ? null : candidate.id(), temp.resolve("workspace"),
                null, verification, failureReason, 0);
        experiments.save(experiment);
        InMemoryPromotionJournal journals = new InMemoryPromotionJournal();
        PromotionPreviewApplicationService service = new PromotionPreviewApplicationService(
                experiments, projects, snapshots, new FixedSnapshotPort(currentFingerprint), journals);
        return new Fixture(service, experiments, journals, experiment);
    }

    private Snapshot snapshot(UUID projectId, String fingerprint, String name) {
        return new Snapshot(UUID.randomUUID(), projectId, fingerprint, temp.resolve(name), Instant.now(), List.of(), List.of());
    }

    private record Fixture(PromotionPreviewApplicationService service,
                           InMemoryExperimentRepository experiments,
                           InMemoryPromotionJournal journals,
                           Experiment experiment) {
    }

    private record FixedSnapshotPort(String currentFingerprint) implements SnapshotPort {
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
            return currentFingerprint;
        }

        @Override
        public String fingerprintWorkspace(Project project, Path workspace, String parentFingerprint) {
            throw new UnsupportedOperationException();
        }
    }
}
