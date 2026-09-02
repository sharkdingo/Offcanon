package com.offcanon.agent.application;

import com.offcanon.agent.domain.AgentRunResult;
import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.infrastructure.memory.InMemoryEventSink;
import com.offcanon.infrastructure.memory.InMemoryExperimentRepository;
import com.offcanon.infrastructure.memory.InMemoryProjectRepository;
import com.offcanon.infrastructure.memory.InMemorySessionRepository;
import com.offcanon.infrastructure.memory.InMemorySessionRunLease;
import com.offcanon.infrastructure.memory.InMemorySnapshotRepository;
import com.offcanon.port.AgentLoopPort;
import com.offcanon.port.SnapshotPort;
import com.offcanon.port.VerificationPort;
import com.offcanon.port.WorkspacePort;
import com.offcanon.project.domain.Project;
import com.offcanon.session.domain.Session;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.verification.domain.VerificationResult;
import com.offcanon.workspace.domain.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class AgentDeferredVerificationTest {
    @TempDir
    Path temp;

    @Test
    void sealsAResultAndLeavesItAwaitingVerificationWhenPolicyIsEmpty() {
        Fixture fixture = fixture(List.of());
        fixture.service.start(fixture.experiment.id());

        Experiment stored = fixture.experiments.findById(fixture.experiment.id()).orElseThrow();
        assertEquals(ExperimentStatus.AGENT_COMPLETED, stored.status());
        assertTrue(stored.resultSnapshotId() != null);
        verify(fixture.verification, never()).verify(any(), any(), any(), any(), any());
        assertTrue(fixture.events.after(fixture.experiment.id(), 0).stream()
                .anyMatch(event -> event.type().equals("VERIFICATION_WAITING")));
    }

    @Test
    void reverifyCanPassAfterCommandsAreAddedAndCanRetryARejectedResult() {
        Fixture fixture = fixture(List.of());
        fixture.service.start(fixture.experiment.id());
        Experiment waiting = fixture.experiments.findById(fixture.experiment.id()).orElseThrow();
        assertEquals(ExperimentStatus.AGENT_COMPLETED, waiting.status());

        ProjectApplicationServiceAdapter.updateCommands(fixture, List.of("test"));
        when(fixture.verification.verify(any(), any(), any(), any(), any()))
                .thenReturn(VerificationResult.failed(List.of(), "still failing"));
        Experiment rejected = fixture.service.reverify(waiting.id());
        assertEquals(ExperimentStatus.REJECTED, rejected.status());
        assertEquals("still failing", rejected.failureReason());

        doAnswer(ignored -> VerificationResult.passed(List.of()))
                .when(fixture.verification).verify(any(), any(), any(), any(), any());
        Experiment verified = fixture.service.reverify(waiting.id());
        assertEquals(ExperimentStatus.VERIFIED, verified.status());
        assertNull(verified.failureReason());
    }

    @Test
    void verificationFailureReturnsTheSealedResultToAReverifiableWaitingState() {
        Fixture fixture = fixture(List.of("test"));
        when(fixture.verification.verify(any(), any(), any(), any(), any()))
                .thenThrow(new DomainException("VERIFICATION_PROCESS_FAILED", "runner unavailable"));

        fixture.service.start(fixture.experiment.id());

        Experiment waiting = fixture.experiments.findById(fixture.experiment.id()).orElseThrow();
        assertEquals(ExperimentStatus.AGENT_COMPLETED, waiting.status());
        assertTrue(waiting.resultSnapshotId() != null);
        assertTrue(waiting.failureReason().contains("VERIFICATION_PROCESS_FAILED"));
        assertTrue(fixture.events.after(waiting.id(), 0).stream()
                .anyMatch(event -> event.type().equals("VERIFICATION_INTERRUPTED")));

        doAnswer(ignored -> VerificationResult.passed(List.of()))
                .when(fixture.verification).verify(any(), any(), any(), any(), any());
        assertEquals(ExperimentStatus.VERIFIED, fixture.service.reverify(waiting.id()).status());
    }

    private Fixture fixture(List<String> commands) {
        Instant now = Instant.now();
        UUID owner = UUID.randomUUID();
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        Project project = projects.save(Project.create(owner, "demo", temp, commands, now));
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        Session session = sessions.save(Session.create(project.id(), "session", now));
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        Experiment experiment = Experiment.create(project.id(), session.id(), "task", now);
        experiments.save(experiment);
        experiment.beginSnapshot();
        experiments.save(experiment);
        UUID baseId = UUID.randomUUID();
        experiment.attachBase(baseId, temp);
        experiments.save(experiment);

        InMemorySnapshotRepository snapshots = new InMemorySnapshotRepository();
        snapshots.save(new Snapshot(baseId, project.id(), "base", temp, now, List.of(), List.of()));
        SnapshotPort snapshotPort = mock(SnapshotPort.class);
        Snapshot result = new Snapshot(UUID.randomUUID(), project.id(), "result", temp, now.plusSeconds(1), List.of(), List.of());
        when(snapshotPort.captureWorkspace(any(), any(), any())).thenReturn(result);
        when(snapshotPort.fingerprintWorkspace(any(), any(), any())).thenReturn("result");
        WorkspacePort workspaces = mock(WorkspacePort.class);
        when(workspaces.createVerificationWorkspace(any(), any())).thenReturn(temp);
        VerificationPort verification = mock(VerificationPort.class);
        AgentLoopPort loop = (current, cancellation, context, settings) ->
                new AgentRunResult("done", 1, "MODEL_FINISH", List.of());
        ExecutorService executor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        InMemoryEventSink events = new InMemoryEventSink();
        AgentApplicationService service = new AgentApplicationService(experiments, projects, snapshots, snapshotPort,
                loop, verification, executor, events, new InMemorySessionRunLease(), workspaces,
                null, null, null, 20, 600, 80_000);
        return new Fixture(projects, experiments, experiment, snapshotPort, verification, service, events);
    }

    private record Fixture(InMemoryProjectRepository projects,
                           InMemoryExperimentRepository experiments,
                           Experiment experiment,
                           SnapshotPort snapshotPort,
                           VerificationPort verification,
                           AgentApplicationService service,
                           InMemoryEventSink events) {
    }

    private static final class ProjectApplicationServiceAdapter {
        private static void updateCommands(Fixture fixture, List<String> commands) {
            Project project = fixture.projects.findById(fixture.experiment.projectId()).orElseThrow();
            fixture.projects.update(project.updated(project.name(), commands));
        }
    }
}
