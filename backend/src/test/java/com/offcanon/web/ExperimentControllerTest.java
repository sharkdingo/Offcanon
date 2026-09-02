package com.offcanon.web;

import com.offcanon.agent.application.AgentApplicationService;
import com.offcanon.application.ExperimentApplicationService;
import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.port.DiffPort;
import com.offcanon.port.EvidenceRepository;
import com.offcanon.port.SnapshotRepository;
import com.offcanon.identity.application.AuthApplicationService;
import com.offcanon.identity.web.IdentityContext;
import com.offcanon.promotion.application.PromotionApplicationService;
import com.offcanon.promotion.application.PromotionPreviewApplicationService;
import com.offcanon.promotion.application.PromotionRecoveryService;
import com.offcanon.promotion.application.PromotionStaleApplicationService;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.workspace.domain.Snapshot;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExperimentControllerTest {
    @Test
    void diffReportsWhenAnEvictedTerminalWorkspaceHasNoResultSnapshot() {
        ExperimentApplicationService experiments = mock(ExperimentApplicationService.class);
        AgentApplicationService agent = mock(AgentApplicationService.class);
        EvidenceRepository evidence = mock(EvidenceRepository.class);
        PromotionApplicationService promotion = mock(PromotionApplicationService.class);
        PromotionPreviewApplicationService preview = mock(PromotionPreviewApplicationService.class);
        PromotionRecoveryService recovery = mock(PromotionRecoveryService.class);
        PromotionStaleApplicationService stale = mock(PromotionStaleApplicationService.class);
        DiffPort diff = mock(DiffPort.class);
        SnapshotRepository snapshots = mock(SnapshotRepository.class);
        UUID experimentId = UUID.randomUUID();
        Experiment failed = Experiment.restore(experimentId, UUID.randomUUID(), UUID.randomUUID(),
                "failed", Instant.now(), ExperimentStatus.FAILED, UUID.randomUUID(), null,
                Path.of("target", "missing-experiment-workspace"), null, null, "provider failed", 0);
        UUID ownerId = UUID.randomUUID();
        IdentityContext identity = mock(IdentityContext.class);
        when(identity.ownerId(org.mockito.ArgumentMatchers.any())).thenReturn(ownerId);
        when(experiments.get(experimentId, ownerId)).thenReturn(failed);
        ExperimentController controller = new ExperimentController(experiments, agent, evidence, promotion,
                preview, recovery, stale, diff, snapshots, identity);

        DomainException error = org.junit.jupiter.api.Assertions.assertThrows(DomainException.class,
                () -> controller.diff(experimentId, mock(HttpServletRequest.class)));

        assertEquals("DIFF_UNAVAILABLE", error.code());
        verify(experiments).get(experimentId, ownerId);
        verifyNoInteractions(diff, snapshots);
    }

    @Test
    void diffUsesSealedResultWhenMutableWorkspaceReferenceIsAbsent() {
        ExperimentApplicationService experiments = mock(ExperimentApplicationService.class);
        AgentApplicationService agent = mock(AgentApplicationService.class);
        EvidenceRepository evidence = mock(EvidenceRepository.class);
        PromotionApplicationService promotion = mock(PromotionApplicationService.class);
        PromotionPreviewApplicationService preview = mock(PromotionPreviewApplicationService.class);
        PromotionRecoveryService recovery = mock(PromotionRecoveryService.class);
        PromotionStaleApplicationService stale = mock(PromotionStaleApplicationService.class);
        DiffPort diff = mock(DiffPort.class);
        SnapshotRepository snapshots = mock(SnapshotRepository.class);
        IdentityContext identity = mock(IdentityContext.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        UUID experimentId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID baseSnapshotId = UUID.randomUUID();
        UUID resultSnapshotId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Path basePath = Path.of("target", "sealed-base").toAbsolutePath();
        Path resultPath = Path.of("target", "sealed-result").toAbsolutePath();
        Experiment sealed = Experiment.restore(experimentId, projectId, UUID.randomUUID(),
                "sealed", Instant.now(), ExperimentStatus.AGENT_COMPLETED, baseSnapshotId, resultSnapshotId,
                null, "done", null, null, 2);
        Snapshot base = new Snapshot(baseSnapshotId, projectId, "base-fingerprint", basePath,
                Instant.now(), List.of(), List.of());
        Snapshot result = new Snapshot(resultSnapshotId, projectId, "result-fingerprint", resultPath,
                Instant.now(), List.of("index.html"), List.of());
        DiffPort.DiffEntry entry = new DiffPort.DiffEntry("index.html", DiffPort.DiffEntry.Change.ADDED,
                0, 12, false, 1, 0, "+hello\n");
        when(identity.ownerId(request)).thenReturn(ownerId);
        when(experiments.get(experimentId, ownerId)).thenReturn(sealed);
        when(snapshots.findById(baseSnapshotId)).thenReturn(Optional.of(base));
        when(snapshots.findById(resultSnapshotId)).thenReturn(Optional.of(result));
        when(diff.compare(base, resultPath)).thenReturn(List.of(entry));
        ExperimentController controller = new ExperimentController(experiments, agent, evidence, promotion,
                preview, recovery, stale, diff, snapshots, identity);

        List<ApiDtos.DiffEntryResponse> response = controller.diff(experimentId, request);

        assertEquals(1, response.size());
        assertEquals("index.html", response.getFirst().path());
        assertEquals("ADDED", response.getFirst().change());
        verify(diff).compare(base, resultPath);
    }

    @Test
    void diffRejectsBaseSnapshotBoundToAnotherProject() {
        ExperimentApplicationService experiments = mock(ExperimentApplicationService.class);
        AgentApplicationService agent = mock(AgentApplicationService.class);
        EvidenceRepository evidence = mock(EvidenceRepository.class);
        PromotionApplicationService promotion = mock(PromotionApplicationService.class);
        PromotionPreviewApplicationService preview = mock(PromotionPreviewApplicationService.class);
        PromotionRecoveryService recovery = mock(PromotionRecoveryService.class);
        PromotionStaleApplicationService stale = mock(PromotionStaleApplicationService.class);
        DiffPort diff = mock(DiffPort.class);
        SnapshotRepository snapshots = mock(SnapshotRepository.class);
        IdentityContext identity = mock(IdentityContext.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        UUID experimentId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID otherProjectId = UUID.randomUUID();
        UUID baseSnapshotId = UUID.randomUUID();
        UUID resultSnapshotId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Experiment experiment = Experiment.restore(experimentId, projectId, UUID.randomUUID(),
                "sealed", Instant.now(), ExperimentStatus.AGENT_COMPLETED, baseSnapshotId,
                resultSnapshotId, null, "done", null, null, 2);
        Snapshot base = new Snapshot(baseSnapshotId, otherProjectId, "base-fingerprint",
                Path.of("target", "foreign-base").toAbsolutePath(), Instant.now(), List.of(), List.of());
        Snapshot result = new Snapshot(resultSnapshotId, projectId, "result-fingerprint",
                Path.of("target", "sealed-result").toAbsolutePath(), Instant.now(), List.of(), List.of());
        when(identity.ownerId(request)).thenReturn(ownerId);
        when(experiments.get(experimentId, ownerId)).thenReturn(experiment);
        when(snapshots.findById(baseSnapshotId)).thenReturn(Optional.of(base));
        when(snapshots.findById(resultSnapshotId)).thenReturn(Optional.of(result));
        ExperimentController controller = new ExperimentController(experiments, agent, evidence, promotion,
                preview, recovery, stale, diff, snapshots, identity);

        DomainException error = org.junit.jupiter.api.Assertions.assertThrows(DomainException.class,
                () -> controller.diff(experimentId, request));

        assertEquals("DIFF_SNAPSHOT_PROJECT_MISMATCH", error.code());
        verifyNoInteractions(diff);
    }

    @Test
    void diffRejectsResultSnapshotBoundToAnotherProject() {
        ExperimentApplicationService experiments = mock(ExperimentApplicationService.class);
        AgentApplicationService agent = mock(AgentApplicationService.class);
        EvidenceRepository evidence = mock(EvidenceRepository.class);
        PromotionApplicationService promotion = mock(PromotionApplicationService.class);
        PromotionPreviewApplicationService preview = mock(PromotionPreviewApplicationService.class);
        PromotionRecoveryService recovery = mock(PromotionRecoveryService.class);
        PromotionStaleApplicationService stale = mock(PromotionStaleApplicationService.class);
        DiffPort diff = mock(DiffPort.class);
        SnapshotRepository snapshots = mock(SnapshotRepository.class);
        IdentityContext identity = mock(IdentityContext.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        UUID experimentId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID otherProjectId = UUID.randomUUID();
        UUID baseSnapshotId = UUID.randomUUID();
        UUID resultSnapshotId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Experiment experiment = Experiment.restore(experimentId, projectId, UUID.randomUUID(),
                "sealed", Instant.now(), ExperimentStatus.AGENT_COMPLETED, baseSnapshotId,
                resultSnapshotId, null, "done", null, null, 2);
        Snapshot base = new Snapshot(baseSnapshotId, projectId, "base-fingerprint",
                Path.of("target", "sealed-base").toAbsolutePath(), Instant.now(), List.of(), List.of());
        Snapshot result = new Snapshot(resultSnapshotId, otherProjectId, "result-fingerprint",
                Path.of("target", "foreign-result").toAbsolutePath(), Instant.now(), List.of(), List.of());
        when(identity.ownerId(request)).thenReturn(ownerId);
        when(experiments.get(experimentId, ownerId)).thenReturn(experiment);
        when(snapshots.findById(baseSnapshotId)).thenReturn(Optional.of(base));
        when(snapshots.findById(resultSnapshotId)).thenReturn(Optional.of(result));
        ExperimentController controller = new ExperimentController(experiments, agent, evidence, promotion,
                preview, recovery, stale, diff, snapshots, identity);

        DomainException error = org.junit.jupiter.api.Assertions.assertThrows(DomainException.class,
                () -> controller.diff(experimentId, request));

        assertEquals("DIFF_SNAPSHOT_PROJECT_MISMATCH", error.code());
        verifyNoInteractions(diff);
    }

    @Test
    void diffReportsWhenTerminalWorkspaceReferenceAndResultAreMissing() {
        ExperimentApplicationService experiments = mock(ExperimentApplicationService.class);
        AgentApplicationService agent = mock(AgentApplicationService.class);
        EvidenceRepository evidence = mock(EvidenceRepository.class);
        PromotionApplicationService promotion = mock(PromotionApplicationService.class);
        PromotionPreviewApplicationService preview = mock(PromotionPreviewApplicationService.class);
        PromotionRecoveryService recovery = mock(PromotionRecoveryService.class);
        PromotionStaleApplicationService stale = mock(PromotionStaleApplicationService.class);
        DiffPort diff = mock(DiffPort.class);
        SnapshotRepository snapshots = mock(SnapshotRepository.class);
        IdentityContext identity = mock(IdentityContext.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        UUID experimentId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Experiment failed = Experiment.restore(experimentId, UUID.randomUUID(), UUID.randomUUID(),
                "failed", Instant.now(), ExperimentStatus.FAILED, UUID.randomUUID(), null,
                null, null, null, "workspace reference missing", 1);
        when(identity.ownerId(request)).thenReturn(ownerId);
        when(experiments.get(experimentId, ownerId)).thenReturn(failed);
        ExperimentController controller = new ExperimentController(experiments, agent, evidence, promotion,
                preview, recovery, stale, diff, snapshots, identity);

        DomainException error = org.junit.jupiter.api.Assertions.assertThrows(DomainException.class,
                () -> controller.diff(experimentId, request));

        assertEquals("DIFF_UNAVAILABLE", error.code());
        verifyNoInteractions(diff, snapshots);
    }

    @Test
    void verifyEndpointChecksOwnershipAndDelegatesToReverification() {
        ExperimentApplicationService experiments = mock(ExperimentApplicationService.class);
        AgentApplicationService agent = mock(AgentApplicationService.class);
        EvidenceRepository evidence = mock(EvidenceRepository.class);
        PromotionApplicationService promotion = mock(PromotionApplicationService.class);
        PromotionPreviewApplicationService preview = mock(PromotionPreviewApplicationService.class);
        PromotionRecoveryService recovery = mock(PromotionRecoveryService.class);
        PromotionStaleApplicationService stale = mock(PromotionStaleApplicationService.class);
        DiffPort diff = mock(DiffPort.class);
        SnapshotRepository snapshots = mock(SnapshotRepository.class);
        IdentityContext identity = mock(IdentityContext.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        UUID experimentId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Experiment waiting = Experiment.restore(experimentId, UUID.randomUUID(), UUID.randomUUID(),
                "waiting", Instant.now(), ExperimentStatus.AGENT_COMPLETED,
                UUID.randomUUID(), UUID.randomUUID(), Path.of("workspace"), "done", null, null, 2);
        when(identity.ownerId(request)).thenReturn(ownerId);
        when(experiments.get(experimentId, ownerId)).thenReturn(waiting);
        when(agent.reverify(experimentId)).thenReturn(waiting);
        ExperimentController controller = new ExperimentController(experiments, agent, evidence, promotion,
                preview, recovery, stale, diff, snapshots, identity);

        ApiDtos.ExperimentResponse response = controller.verify(experimentId, request);

        assertEquals(ExperimentStatus.AGENT_COMPLETED.name(), response.status());
        verify(experiments).get(experimentId, ownerId);
        verify(agent).reverify(experimentId);
    }
}
