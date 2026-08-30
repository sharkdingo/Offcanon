package com.offcanon.memory.application;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.infrastructure.memory.InMemoryEvidenceRepository;
import com.offcanon.infrastructure.memory.InMemoryExperimentRepository;
import com.offcanon.infrastructure.memory.InMemorySessionRepository;
import com.offcanon.infrastructure.memory.InMemorySnapshotRepository;
import com.offcanon.infrastructure.memory.InMemoryTaskMemoryRepository;
import com.offcanon.memory.domain.MemoryPatch;
import com.offcanon.memory.domain.TaskMemoryKind;
import com.offcanon.memory.domain.TaskMemoryOrigin;
import com.offcanon.memory.domain.TaskMemoryProjection;
import com.offcanon.memory.domain.TaskMemoryStatus;
import com.offcanon.memory.domain.TaskMemoryTrust;
import com.offcanon.session.domain.Session;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.verification.domain.Evidence;
import com.offcanon.verification.domain.VerificationResult;
import com.offcanon.workspace.domain.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskMemoryApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");

    private final UUID projectId = UUID.randomUUID();
    private final InMemoryTaskMemoryRepository memories = new InMemoryTaskMemoryRepository();
    private final InMemorySessionRepository sessions = new InMemorySessionRepository();
    private final InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
    private final InMemorySnapshotRepository snapshots = new InMemorySnapshotRepository();
    private final InMemoryEvidenceRepository evidence = new InMemoryEvidenceRepository();
    private TaskMemoryApplicationService service;
    private Session session;
    private Experiment experiment;
    private Snapshot base;
    private Snapshot result;

    @BeforeEach
    void setUp() {
        service = new TaskMemoryApplicationService(memories, sessions, experiments, snapshots, evidence,
                new TaskMemoryProjector(), () -> NOW);
        session = Session.create(projectId, "remember safely", NOW);
        sessions.save(session);
        base = snapshot("base-fingerprint", "base");
        result = snapshot("result-fingerprint", "result");
        snapshots.save(base);
        snapshots.save(result);
        experiment = verifiedExperiment();
    }

    @Test
    void recordsThreeOriginsAndProjectsByCurrentSnapshotFingerprint() {
        var user = service.recordUserAuthored(session.id(), experiment.id(), base.id(),
                MemoryPatch.of(TaskMemoryKind.CONSTRAINT, "Keep the CLI JSON-compatible"));
        var agent = service.recordAgentReported(session.id(), experiment.id(), base.id(),
                MemoryPatch.of(TaskMemoryKind.HYPOTHESIS, "The parser can be simplified"));
        Evidence proof = trustedEvidence();
        evidence.save(proof);
        var verified = service.recordVerifiedSystem(session.id(), experiment.id(), result.id(),
                new MemoryPatch(TaskMemoryKind.VERIFIED_FACT, "All trusted tests pass",
                        List.of(proof.id()), List.of()));

        assertEquals(TaskMemoryOrigin.USER_AUTHORED, user.origin());
        assertEquals(TaskMemoryTrust.USER_CONFIRMED, user.trust());
        assertEquals(TaskMemoryStatus.ACCEPTED, user.status());
        assertEquals(TaskMemoryOrigin.AGENT_REPORTED, agent.origin());
        assertEquals(TaskMemoryStatus.PROPOSED, agent.status());
        assertEquals(TaskMemoryOrigin.VERIFIED_SYSTEM, verified.origin());
        assertEquals(TaskMemoryTrust.VERIFIED, verified.trust());
        assertEquals(result.id(), verified.sourceSnapshotId());
        assertEquals(List.of(proof.id()), verified.sourceEvidenceIds());

        TaskMemoryProjection projection = service.project(session.id(), result.id());
        assertEquals(List.of(verified), projection.current().stream()
                .map(TaskMemoryProjection.ProjectedMemory::revision).toList());
        assertEquals(List.of(user), projection.stale().stream()
                .map(TaskMemoryProjection.ProjectedMemory::revision).toList());
        assertEquals(List.of(agent), projection.proposed().stream()
                .map(TaskMemoryProjection.ProjectedMemory::revision).toList());
        assertEquals(TaskMemoryProjection.Freshness.STALE, projection.proposed().getFirst().freshness());
    }

    @Test
    void verifiedSystemMemoryRejectsAgentObservationEvidence() {
        Evidence observation = Evidence.command(experiment.id(), result.id(), "npm test", ".", 0,
                "ok", "", NOW, NOW.plusSeconds(1), Duration.ofSeconds(1), false, false, "host");
        evidence.save(observation);

        DomainException error = assertThrows(DomainException.class,
                () -> service.recordVerifiedSystem(session.id(), experiment.id(), result.id(),
                        new MemoryPatch(TaskMemoryKind.VERIFIED_FACT, "Tests pass",
                                List.of(observation.id()), List.of())));

        assertEquals("TASK_MEMORY_UNTRUSTED_EVIDENCE", error.code());
    }

    @Test
    void verifiedSystemMemoryRejectsTrustedButFailedEvidence() {
        Evidence failed = new Evidence(UUID.randomUUID(), experiment.id(), result.id(), "VERIFICATION",
                "mvn test", ".", 1, "", "failed", NOW, NOW.plusSeconds(1), Duration.ofSeconds(1),
                false, true, "trusted-verification", false);
        evidence.save(failed);

        DomainException error = assertThrows(DomainException.class,
                () -> service.recordVerifiedSystem(session.id(), experiment.id(), result.id(),
                        new MemoryPatch(TaskMemoryKind.VERIFIED_FACT, "Tests pass",
                                List.of(failed.id()), List.of())));

        assertEquals("TASK_MEMORY_INVALID_EVIDENCE", error.code());
    }

    @Test
    void untrustedAuthorsCannotCreateVerifiedFacts() {
        DomainException error = assertThrows(DomainException.class,
                () -> service.recordAgentReported(session.id(), experiment.id(), base.id(),
                        MemoryPatch.of(TaskMemoryKind.VERIFIED_FACT, "Everything is correct")));

        assertEquals("TASK_MEMORY_UNTRUSTED_FACT", error.code());
        assertEquals(List.of(), memories.findBySessionId(session.id()));
    }

    @Test
    void rejectsSnapshotThatIsNotBoundToTheSourceExperiment() {
        Snapshot unrelated = snapshot("unrelated", "unrelated");
        snapshots.save(unrelated);

        DomainException error = assertThrows(DomainException.class,
                () -> service.recordUserAuthored(session.id(), experiment.id(), unrelated.id(),
                        MemoryPatch.of(TaskMemoryKind.GOAL, "Do something")));

        assertEquals("TASK_MEMORY_PROVENANCE_MISMATCH", error.code());
    }

    private Snapshot snapshot(String fingerprint, String directory) {
        return new Snapshot(UUID.randomUUID(), projectId, fingerprint, Path.of("target", "memory", directory),
                NOW, List.of(), List.of());
    }

    private Experiment verifiedExperiment() {
        Experiment value = Experiment.create(projectId, session.id(), "implement memory", NOW);
        experiments.save(value);
        value.beginSnapshot();
        experiments.save(value);
        value.attachBase(base.id(), Path.of("target", "memory", "workspace"));
        experiments.save(value);
        value.start();
        experiments.save(value);
        value.markAgentCompleted("done");
        experiments.save(value);
        value.sealResult(result.id());
        experiments.save(value);
        value.beginVerification();
        experiments.save(value);
        value.markVerified(VerificationResult.passed(List.of()));
        return experiments.save(value);
    }

    private Evidence trustedEvidence() {
        return new Evidence(UUID.randomUUID(), experiment.id(), result.id(), "VERIFICATION",
                "npm test", ".", 0, "ok", "", NOW, NOW.plusSeconds(1), Duration.ofSeconds(1),
                false, true, "host", false);
    }
}
