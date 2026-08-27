package com.pico.infrastructure.verification;

import com.pico.experiment.domain.Experiment;
import com.pico.infrastructure.memory.InMemoryEvidenceRepository;
import com.pico.infrastructure.memory.InMemorySnapshotRepository;
import com.pico.port.CommandExecutor;
import com.pico.port.SnapshotPort;
import com.pico.project.domain.Project;
import com.pico.shared.domain.DomainException;
import com.pico.verification.domain.VerificationPurpose;
import com.pico.workspace.domain.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrustedVerificationAdapterTest {
    @TempDir
    Path temp;

    @Test
    void sourceMutationInvalidatesOtherwisePassingCommandEvidence() {
        UUID projectId = UUID.randomUUID();
        Project project = new Project(projectId, "demo", temp.resolve("canonical"),
                List.of("mutating-check"), Instant.now(), 0);
        Snapshot base = new Snapshot(UUID.randomUUID(), projectId, "base", temp.resolve("base"),
                Instant.now(), List.of(), List.of());
        Snapshot result = new Snapshot(UUID.randomUUID(), projectId, "candidate", temp.resolve("result"),
                Instant.now(), List.of(), List.of());
        InMemorySnapshotRepository snapshots = new InMemorySnapshotRepository();
        snapshots.save(base);
        Experiment experiment = Experiment.create(projectId, UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(base.id(), temp.resolve("workspace"));
        CommandExecutor command = (value, cwd, timeout, environment) ->
                new CommandExecutor.CommandExecution(0, "passed", "", Duration.ofMillis(5), false);
        SnapshotPort fingerprint = new SnapshotPort() {
            @Override public Snapshot capture(Project value) { throw new UnsupportedOperationException(); }
            @Override public Snapshot captureWorkspace(Project value, Path workspace, String parent) { throw new UnsupportedOperationException(); }
            @Override public String currentFingerprint(Project value) { throw new UnsupportedOperationException(); }
            @Override public String fingerprintWorkspace(Project value, Path workspace, String parent) { return "tampered"; }
        };
        InMemoryEvidenceRepository evidence = new InMemoryEvidenceRepository();
        TrustedVerificationAdapter verifier = new TrustedVerificationAdapter(command, evidence, fingerprint, snapshots, 5);

        DomainException error = assertThrows(DomainException.class, () -> verifier.verify(
                project, experiment, result, temp.resolve("verification"), VerificationPurpose.EXPERIMENT_RESULT));

        assertEquals("VERIFICATION_MUTATED_SOURCE", error.code());
        var persisted = evidence.findByExperimentId(experiment.id());
        assertEquals(1, persisted.size());
        assertFalse(persisted.get(0).trusted());
        assertEquals("VERIFICATION", persisted.get(0).kind());
    }
}
