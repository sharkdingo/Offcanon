package com.offcanon.infrastructure.workspace;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.infrastructure.memory.InMemoryExperimentRepository;
import com.offcanon.infrastructure.memory.InMemoryProjectRepository;
import com.offcanon.infrastructure.memory.InMemoryPromotionJournal;
import com.offcanon.port.ExperimentRepository;
import com.offcanon.port.ProjectRepository;
import com.offcanon.port.PromotionJournalPort;
import com.offcanon.project.domain.Project;
import com.offcanon.promotion.domain.PromotionJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RuntimeRetentionServiceTest {
    @TempDir
    Path temp;

    @Test
    void scheduledCleanupWaitsForApplicationReady() {
        ProjectRepository projects = mock(ProjectRepository.class);
        ExperimentRepository experiments = mock(ExperimentRepository.class);
        PromotionJournalPort journals = mock(PromotionJournalPort.class);
        when(projects.findAll()).thenReturn(List.of());
        when(journals.findOpen()).thenReturn(List.of());

        RuntimeRetentionService service = new RuntimeRetentionService(temp.resolve("data"), projects,
                experiments, journals, Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO);

        service.scheduledCleanup();
        verifyNoInteractions(projects, experiments, journals);

        service.cleanupOnApplicationReady();
        verify(projects, atLeastOnce()).findAll();
        verify(journals).findOpen();
    }

    @Test
    void removesExpiredUnprotectedMaterializationsAndKeepsLifecycleProtectedTrees() throws Exception {
        Instant now = Instant.parse("2026-08-28T12:00:00Z");
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        InMemoryPromotionJournal journals = new InMemoryPromotionJournal();
        Project project = projects.save(Project.create(java.util.UUID.randomUUID(), "demo", temp.resolve("canonical"), List.of(), now));

        UUID baseSnapshot = UUID.randomUUID();
        UUID resultSnapshot = UUID.randomUUID();
        Experiment verified = Experiment.restore(UUID.randomUUID(), project.id(), UUID.randomUUID(),
                "verified", now.minus(Duration.ofDays(10)), ExperimentStatus.VERIFIED,
                baseSnapshot, resultSnapshot, null, "summary", null, null, 0);
        Experiment verifying = Experiment.restore(UUID.randomUUID(), project.id(), UUID.randomUUID(),
                "verifying", now.minus(Duration.ofDays(10)), ExperimentStatus.VERIFYING,
                UUID.randomUUID(), UUID.randomUUID(), null, null, null, null, 0);
        Experiment recovery = Experiment.restore(UUID.randomUUID(), project.id(), UUID.randomUUID(),
                "recovery", now.minus(Duration.ofDays(10)), ExperimentStatus.RECOVERY_REQUIRED,
                UUID.randomUUID(), UUID.randomUUID(), null, null, null, "recovery", 0);
        experiments.save(verified);
        experiments.save(verifying);
        experiments.save(recovery);

        Path snapshots = Files.createDirectories(temp.resolve("data/snapshots"));
        Path protectedBase = oldDirectory(snapshots.resolve(baseSnapshot.toString()), now);
        Path protectedResult = oldDirectory(snapshots.resolve(resultSnapshot.toString()), now);
        Path evictableSnapshot = oldDirectory(snapshots.resolve(UUID.randomUUID().toString()), now);
        assertTrue(Files.isDirectory(protectedBase));
        assertTrue(Files.isDirectory(protectedResult));
        assertTrue(Files.isDirectory(evictableSnapshot));

        Path verificationRoot = Files.createDirectories(temp.resolve("data/verification-workspaces"));
        Path activeVerification = oldDirectory(verificationRoot.resolve(verifying.id().toString()).resolve("attempt-active"), now);
        Path expiredVerification = oldDirectory(verificationRoot.resolve(verified.id().toString()).resolve("attempt-expired"), now);

        Path candidateRoot = Files.createDirectories(temp.resolve("data/promotion-candidates"));
        Path recoveryCandidate = oldDirectory(candidateRoot.resolve(recovery.id().toString()).resolve("attempt-recovery"), now);
        Path expiredCandidate = oldDirectory(candidateRoot.resolve(verified.id().toString()).resolve("attempt-expired"), now);

        Path openCandidate = oldDirectory(candidateRoot.resolve(UUID.randomUUID().toString()).resolve("attempt-open"), now);
        journals.create(PromotionJournal.create(UUID.randomUUID(), project.id(), "base", "candidate",
                openCandidate, List.of(), Map.of(), Map.of(), "owner", now,
                now.plus(Duration.ofHours(1))));

        RuntimeRetentionService service = new RuntimeRetentionService(temp.resolve("data"), projects,
                experiments, journals, Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO);
        RuntimeRetentionService.CleanupReport report = service.cleanup(now);

        assertTrue(report.verificationWorkspaces() >= 1);
        assertTrue(report.promotionCandidates() >= 1);
        assertTrue(report.snapshotMaterializations() >= 1);
        assertTrue(Files.exists(protectedBase));
        assertTrue(Files.exists(protectedResult));
        assertTrue(Files.exists(activeVerification));
        assertTrue(Files.exists(recoveryCandidate));
        assertTrue(Files.exists(openCandidate));
        assertFalse(Files.exists(expiredVerification));
        assertFalse(Files.exists(expiredCandidate));
        assertFalse(Files.exists(evictableSnapshot));
    }

    @Test
    void removesFailedSourceOnlyAfterSuccessorWorkspaceIsDurable() throws Exception {
        Instant now = Instant.parse("2026-08-28T12:00:00Z");
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        InMemoryPromotionJournal journals = new InMemoryPromotionJournal();
        Project project = projects.save(Project.create(java.util.UUID.randomUUID(), "demo", temp.resolve("canonical"), List.of(), now));

        UUID sourceId = UUID.randomUUID();
        UUID successorId = UUID.randomUUID();
        Path sourceWorkspace = oldDirectory(temp.resolve("data/experiments").resolve(sourceId.toString()), now);
        Path successorWorkspace = oldDirectory(temp.resolve("data/experiments").resolve(successorId.toString()), now);
        Experiment source = Experiment.restore(sourceId, project.id(), UUID.randomUUID(),
                "failed", now.minus(Duration.ofDays(2)), ExperimentStatus.FAILED,
                UUID.randomUUID(), null, sourceWorkspace, null, null, "provider failed", 0);
        Experiment successor = Experiment.restore(successorId, project.id(), source.sessionId(), sourceId,
                "continue", now.minus(Duration.ofHours(1)), ExperimentStatus.READY_TO_RUN,
                UUID.randomUUID(), null, successorWorkspace, null, null, null, 0);
        experiments.save(source);
        experiments.save(successor);

        RuntimeRetentionService service = new RuntimeRetentionService(temp.resolve("data"), projects,
                experiments, journals, Duration.ZERO, Duration.ofDays(1), Duration.ofDays(1), Duration.ofDays(1));
        service.cleanup(now);

        assertFalse(Files.exists(sourceWorkspace));
        assertTrue(Files.exists(successorWorkspace));
    }

    @Test
    void removesStalePartialSourceAfterSuccessorWorkspaceIsDurable() throws Exception {
        Instant now = Instant.parse("2026-08-28T12:00:00Z");
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        InMemoryPromotionJournal journals = new InMemoryPromotionJournal();
        Project project = projects.save(Project.create(java.util.UUID.randomUUID(), "demo", temp.resolve("canonical"), List.of(), now));
        UUID sourceId = UUID.randomUUID();
        UUID successorId = UUID.randomUUID();
        Path sourceWorkspace = oldDirectory(temp.resolve("data/experiments").resolve(sourceId.toString()), now);
        Path successorWorkspace = oldDirectory(temp.resolve("data/experiments").resolve(successorId.toString()), now);
        Experiment source = Experiment.restore(sourceId, project.id(), UUID.randomUUID(),
                "stale", now.minus(Duration.ofDays(2)), ExperimentStatus.STALE,
                UUID.randomUUID(), null, sourceWorkspace, null, null, "canonical changed", 0);
        Experiment successor = Experiment.restore(successorId, project.id(), source.sessionId(), sourceId,
                "continue", now.minus(Duration.ofHours(1)), ExperimentStatus.READY_TO_RUN,
                UUID.randomUUID(), null, successorWorkspace, null, null, null, 0);
        experiments.save(source);
        experiments.save(successor);

        RuntimeRetentionService service = new RuntimeRetentionService(temp.resolve("data"), projects,
                experiments, journals, Duration.ZERO, Duration.ofDays(1), Duration.ofDays(1), Duration.ofDays(1));
        service.cleanup(now);

        assertFalse(Files.exists(sourceWorkspace));
        assertTrue(Files.exists(successorWorkspace));
    }

    @Test
    void removesTerminalWorkspaceWhenResultSnapshotIsSealed() throws Exception {
        Instant now = Instant.parse("2026-08-28T12:00:00Z");
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        InMemoryPromotionJournal journals = new InMemoryPromotionJournal();
        Project project = projects.save(Project.create(java.util.UUID.randomUUID(), "demo", temp.resolve("canonical"), List.of(), now));

        UUID experimentId = UUID.randomUUID();
        Path workspace = oldDirectory(temp.resolve("data/experiments").resolve(experimentId.toString()), now);
        Experiment promoted = Experiment.restore(experimentId, project.id(), UUID.randomUUID(),
                "promoted", now.minus(Duration.ofDays(2)), ExperimentStatus.PROMOTED,
                UUID.randomUUID(), UUID.randomUUID(), workspace, "done", null, null, 0);
        experiments.save(promoted);

        RuntimeRetentionService service = new RuntimeRetentionService(temp.resolve("data"), projects,
                experiments, journals, Duration.ofDays(1), Duration.ofDays(1), Duration.ofDays(1), Duration.ofDays(1));
        RuntimeRetentionService.CleanupReport report = service.cleanup(now);

        assertFalse(Files.exists(workspace));
        assertTrue(report.experimentWorkspaces() >= 1);
    }

    @Test
    void keepsFailedSourceWhenSuccessorHasNotFinishedForking() throws Exception {
        Instant now = Instant.parse("2026-08-28T12:00:00Z");
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        InMemoryPromotionJournal journals = new InMemoryPromotionJournal();
        Project project = projects.save(Project.create(java.util.UUID.randomUUID(), "demo", temp.resolve("canonical"), List.of(), now));
        UUID sourceId = UUID.randomUUID();
        Path sourceWorkspace = oldDirectory(temp.resolve("data/experiments").resolve(sourceId.toString()), now);
        Experiment source = Experiment.restore(sourceId, project.id(), UUID.randomUUID(),
                "failed", now.minus(Duration.ofDays(2)), ExperimentStatus.REJECTED,
                UUID.randomUUID(), null, sourceWorkspace, null, null, "verification failed", 0);
        experiments.save(source);

        RuntimeRetentionService service = new RuntimeRetentionService(temp.resolve("data"), projects,
                experiments, journals, Duration.ZERO, Duration.ofDays(1), Duration.ofDays(1), Duration.ofDays(1));
        service.cleanup(now);

        assertTrue(Files.exists(sourceWorkspace));
    }

    private Path oldDirectory(Path path, Instant now) throws Exception {
        Files.createDirectories(path);
        Files.writeString(path.resolve("marker.txt"), "runtime");
        FileTime old = FileTime.from(now.minus(Duration.ofDays(2)));
        Files.setLastModifiedTime(path.resolve("marker.txt"), old);
        Files.setLastModifiedTime(path, old);
        return path;
    }
}
