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
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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
}
