package com.offcanon.agent.application;

import com.offcanon.agent.domain.AgentRunResult;
import com.offcanon.agent.domain.AgentRunSettings;
import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.identity.domain.UserSettings;
import com.offcanon.infrastructure.memory.InMemoryEventSink;
import com.offcanon.infrastructure.memory.InMemoryExperimentRepository;
import com.offcanon.infrastructure.memory.InMemoryProjectRepository;
import com.offcanon.infrastructure.memory.InMemorySessionRepository;
import com.offcanon.infrastructure.memory.InMemorySessionRunLease;
import com.offcanon.infrastructure.memory.InMemorySnapshotRepository;
import com.offcanon.infrastructure.memory.InMemoryUserSettingsRepository;
import com.offcanon.port.AgentLoopPort;
import com.offcanon.port.CancellationPort;
import com.offcanon.port.SnapshotPort;
import com.offcanon.port.ToolRegistry;
import com.offcanon.port.UserSettingsRepository;
import com.offcanon.port.VerificationPort;
import com.offcanon.port.WorkspacePort;
import com.offcanon.project.domain.Project;
import com.offcanon.session.domain.Session;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.verification.domain.VerificationPurpose;
import com.offcanon.verification.domain.VerificationResult;
import com.offcanon.workspace.domain.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentSettingsIntegrationTest {
    @TempDir
    Path temp;

    @Test
    void resolvesSettingsFromTheProjectOwnerForEachRun() {
        Instant now = Instant.parse("2026-08-28T00:00:00Z");
        UUID owner = UUID.randomUUID();
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        Project project = projects.save(Project.create(owner, "demo", temp, List.of("type service.txt"), now));
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        Session session = sessions.save(Session.create(project.id(), "session", now));
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        Experiment experiment = Experiment.create(project.id(), session.id(), "update service", now);
        experiments.save(experiment);
        experiment.beginSnapshot();
        experiments.save(experiment);
        UUID baseId = UUID.randomUUID();
        experiment.attachBase(baseId, temp);
        experiments.save(experiment);

        InMemorySnapshotRepository snapshots = new InMemorySnapshotRepository();
        snapshots.save(new Snapshot(baseId, project.id(), "base", temp, now, List.of(), List.of()));
        InMemoryUserSettingsRepository settings = new InMemoryUserSettingsRepository();
        settings.save(UserSettings.defaults(owner, now));
        settings.save(UserSettings.defaults(owner, now).updated("dark", "en-US", "https://runtime.example/v1",
                "runtime-model", "runtime-key", 7, 120, 12_000, now.plusSeconds(1)));

        AtomicReference<AgentRunSettings> captured = new AtomicReference<>();
        AgentLoopPort loop = new AgentLoopPort() {
            @Override
            public AgentRunResult run(Experiment current,
                                      CancellationPort cancellation,
                                      java.util.Optional<com.offcanon.agent.domain.SessionContext> context,
                                      java.util.Optional<AgentRunSettings> runSettings) {
                captured.set(runSettings.orElseThrow());
                return new AgentRunResult("done", 1, "MODEL_FINISH", List.of());
            }
        };

        Snapshot result = new Snapshot(UUID.randomUUID(), project.id(), "result", temp, now.plusSeconds(2), List.of(), List.of());
        SnapshotPort snapshotPort = mock(SnapshotPort.class);
        when(snapshotPort.captureWorkspace(any(), any(), any())).thenReturn(result);
        when(snapshotPort.fingerprintWorkspace(any(), any(), any())).thenReturn("result");
        WorkspacePort workspaces = mock(WorkspacePort.class);
        when(workspaces.createVerificationWorkspace(any(), any())).thenReturn(temp);
        VerificationPort verification = mock(VerificationPort.class);
        when(verification.verify(any(), any(), any(), any(), any())).thenReturn(VerificationResult.passed(List.of()));
        ExecutorService executor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        InMemoryEventSink events = new InMemoryEventSink();
        AgentApplicationService service = new AgentApplicationService(experiments, projects, snapshots, snapshotPort,
                loop, verification, executor, events, new InMemorySessionRunLease(), workspaces, settings, null, null,
                20, 600, 80_000);

        service.start(experiment.id());

        assertNotNull(captured.get());
        assertEquals(7, captured.get().maxSteps());
        assertEquals(120, captured.get().runTimeoutSeconds());
        assertEquals(12_000, captured.get().contextLimitChars());
        assertEquals("https://runtime.example/v1", captured.get().modelEndpoint());
        assertEquals("runtime-model", captured.get().modelName());
        assertEquals(ExperimentStatus.VERIFIED, experiments.findById(experiment.id()).orElseThrow().status());
        var configEvent = events.after(experiment.id(), 0).stream()
                .filter(event -> event.type().equals("RUN_CONFIGURATION_RESOLVED"))
                .findFirst().orElseThrow();
        assertEquals("ACCOUNT_SETTINGS", configEvent.payload().get("source"));
        assertEquals(7, configEvent.payload().get("maxSteps"));
        assertEquals(120L, configEvent.payload().get("runTimeoutSeconds"));
        assertEquals(12_000, configEvent.payload().get("contextLimitChars"));
        assertEquals("https://runtime.example/v1", configEvent.payload().get("modelEndpoint"));
        assertEquals("runtime-model", configEvent.payload().get("modelName"));
        assertEquals("ACCOUNT_SETTINGS", configEvent.payload().get("modelEndpointSource"));
        assertEquals("ACCOUNT_SETTINGS", configEvent.payload().get("modelNameSource"));
        assertEquals(true, configEvent.payload().get("effective"));
    }

    @Test
    void rejectsMissingModelConfigurationBeforeAdvancingThePreparedExperiment() {
        Instant now = Instant.parse("2026-08-28T00:00:00Z");
        UUID owner = UUID.randomUUID();
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        Project project = projects.save(Project.create(owner, "demo", temp, List.of(), now));
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        Experiment experiment = Experiment.create(project.id(), UUID.randomUUID(), "update service", now);
        experiments.save(experiment);
        experiment.beginSnapshot();
        experiments.save(experiment);
        experiment.attachBase(UUID.randomUUID(), temp);
        experiments.save(experiment);
        InMemoryUserSettingsRepository settings = new InMemoryUserSettingsRepository();
        settings.save(UserSettings.defaults(owner, now));
        ExecutorService executor = mock(ExecutorService.class);

        AgentApplicationService service = new AgentApplicationService(experiments, projects,
                new InMemorySnapshotRepository(), mock(SnapshotPort.class), mock(AgentLoopPort.class),
                mock(VerificationPort.class), executor, new InMemoryEventSink(),
                new InMemorySessionRunLease(), mock(WorkspacePort.class), settings, null, null,
                20, 600, 80_000);

        DomainException error = assertThrows(DomainException.class, () -> service.start(experiment.id()));

        assertEquals("MODEL_NOT_CONFIGURED", error.code());
        assertEquals(ExperimentStatus.READY_TO_RUN,
                experiments.findById(experiment.id()).orElseThrow().status());
        verify(executor, never()).execute(any());
    }

    @Test
    void rejectsInvalidModelEndpointBeforeAdvancingThePreparedExperiment() {
        Instant now = Instant.parse("2026-08-28T00:00:00Z");
        UUID owner = UUID.randomUUID();
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        Project project = projects.save(Project.create(owner, "demo", temp, List.of(), now));
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        Experiment experiment = Experiment.create(project.id(), UUID.randomUUID(), "update service", now);
        experiments.save(experiment);
        experiment.beginSnapshot();
        experiments.save(experiment);
        experiment.attachBase(UUID.randomUUID(), temp);
        experiments.save(experiment);

        // A repository can contain legacy or externally migrated settings that
        // predate the current UserSettings invariant. The run boundary must
        // still apply the shared endpoint policy before changing experiment
        // state, so model adapters never receive an ambiguous destination.
        UserSettings configured = mock(UserSettings.class);
        when(configured.agentMaxSteps()).thenReturn(20);
        when(configured.agentRunTimeoutSeconds()).thenReturn(600L);
        when(configured.contextLimitChars()).thenReturn(80_000);
        when(configured.modelEndpoint()).thenReturn("ftp://models.example/v1");
        when(configured.modelName()).thenReturn("runtime-model");
        when(configured.modelApiKey()).thenReturn("runtime-key");
        UserSettingsRepository settings = mock(UserSettingsRepository.class);
        when(settings.findByUserId(owner)).thenReturn(java.util.Optional.of(configured));
        ExecutorService executor = mock(ExecutorService.class);

        AgentApplicationService service = new AgentApplicationService(experiments, projects,
                new InMemorySnapshotRepository(), mock(SnapshotPort.class), mock(AgentLoopPort.class),
                mock(VerificationPort.class), executor, new InMemoryEventSink(),
                new InMemorySessionRunLease(), mock(WorkspacePort.class), settings, null, null,
                20, 600, 80_000);

        DomainException error = assertThrows(DomainException.class, () -> service.start(experiment.id()));

        assertEquals("MODEL_ENDPOINT_INVALID", error.code());
        assertEquals(ExperimentStatus.READY_TO_RUN,
                experiments.findById(experiment.id()).orElseThrow().status());
        verify(executor, never()).execute(any());
    }
}
