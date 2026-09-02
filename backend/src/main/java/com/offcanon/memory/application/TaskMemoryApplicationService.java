package com.offcanon.memory.application;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.memory.domain.MemoryPatch;
import com.offcanon.memory.domain.TaskMemoryKind;
import com.offcanon.memory.domain.TaskMemoryOrigin;
import com.offcanon.memory.domain.TaskMemoryProjection;
import com.offcanon.memory.domain.TaskMemoryRevision;
import com.offcanon.memory.domain.TaskMemoryStatus;
import com.offcanon.memory.domain.TaskMemoryTrust;
import com.offcanon.port.ClockPort;
import com.offcanon.port.EvidenceRepository;
import com.offcanon.port.ExperimentRepository;
import com.offcanon.port.SessionRepository;
import com.offcanon.port.SnapshotRepository;
import com.offcanon.port.TaskMemoryRepository;
import com.offcanon.session.domain.Session;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.shared.web.NotFoundException;
import com.offcanon.verification.domain.Evidence;
import com.offcanon.workspace.domain.Snapshot;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Validates every provenance edge before appending an immutable memory revision.
 * Callers provide IDs, never fingerprints or trust labels.
 */
@Service
public class TaskMemoryApplicationService {
    private static final int APPEND_ATTEMPTS = 8;

    private final TaskMemoryRepository memories;
    private final SessionRepository sessions;
    private final ExperimentRepository experiments;
    private final SnapshotRepository snapshots;
    private final EvidenceRepository evidence;
    private final TaskMemoryProjector projector;
    private final ClockPort clock;

    public TaskMemoryApplicationService(TaskMemoryRepository memories,
                                        SessionRepository sessions,
                                        ExperimentRepository experiments,
                                        SnapshotRepository snapshots,
                                        EvidenceRepository evidence,
                                        TaskMemoryProjector projector,
                                        ClockPort clock) {
        this.memories = memories;
        this.sessions = sessions;
        this.experiments = experiments;
        this.snapshots = snapshots;
        this.evidence = evidence;
        this.projector = projector;
        this.clock = clock;
    }

    public TaskMemoryRevision recordUserAuthored(UUID sessionId,
                                                 UUID sourceExperimentId,
                                                 UUID sourceSnapshotId,
                                                 MemoryPatch patch) {
        rejectVerifiedFact(patch, TaskMemoryOrigin.USER_AUTHORED);
        return append(sessionId, sourceExperimentId, sourceSnapshotId, patch,
                TaskMemoryOrigin.USER_AUTHORED, TaskMemoryTrust.USER_CONFIRMED, TaskMemoryStatus.ACCEPTED,
                false);
    }

    public TaskMemoryRevision recordAgentReported(UUID sessionId,
                                                  UUID sourceExperimentId,
                                                  UUID sourceSnapshotId,
                                                  MemoryPatch patch) {
        rejectVerifiedFact(patch, TaskMemoryOrigin.AGENT_REPORTED);
        return append(sessionId, sourceExperimentId, sourceSnapshotId, patch,
                TaskMemoryOrigin.AGENT_REPORTED, TaskMemoryTrust.AGENT_REPORTED, TaskMemoryStatus.PROPOSED,
                false);
    }

    public TaskMemoryRevision recordVerifiedSystem(UUID sessionId,
                                                   UUID sourceExperimentId,
                                                   UUID resultSnapshotId,
                                                   MemoryPatch patch) {
        Session session = session(sessionId);
        Experiment experiment = experiment(sourceExperimentId);
        Snapshot snapshot = snapshot(resultSnapshotId);
        validateScope(session, experiment, snapshot);
        if (experiment.resultSnapshotId() == null || !experiment.resultSnapshotId().equals(resultSnapshotId)
                || experiment.verificationResult() == null || !experiment.verificationResult().passed()) {
            throw new DomainException("TASK_MEMORY_UNVERIFIED_SOURCE",
                    "Verified system memory requires the Experiment's trusted, passing result Snapshot");
        }
        TaskMemoryTrust trust = experiment.status() == ExperimentStatus.PROMOTED
                ? TaskMemoryTrust.PROMOTED : TaskMemoryTrust.VERIFIED;
        return appendValidated(session, experiment, snapshot, patch,
                TaskMemoryOrigin.VERIFIED_SYSTEM, trust, TaskMemoryStatus.ACCEPTED, true);
    }

    public TaskMemoryProjection project(UUID sessionId, UUID currentSnapshotId) {
        Session session = session(sessionId);
        Snapshot snapshot = snapshot(currentSnapshotId);
        if (!snapshot.projectId().equals(session.projectId())) {
            throw new DomainException("TASK_MEMORY_SCOPE_MISMATCH",
                    "Task memory projection Snapshot does not belong to the Session project");
        }
        List<TaskMemoryRevision> ledger = memories.findBySessionId(session.id());
        Set<UUID> invalidatedVerifiedExperiments = ledger.stream()
                .filter(revision -> revision.kind() == TaskMemoryKind.VERIFIED_FACT)
                .filter(revision -> experiments.findById(revision.sourceExperimentId())
                        .map(source -> source.status() != ExperimentStatus.VERIFIED
                                && source.status() != ExperimentStatus.PROMOTED)
                        .orElse(true))
                .map(TaskMemoryRevision::sourceExperimentId)
                .collect(java.util.stream.Collectors.toSet());
        return projector.project(session.projectId(), session.id(), snapshot.fingerprint(), ledger,
                invalidatedVerifiedExperiments);
    }

    private TaskMemoryRevision append(UUID sessionId,
                                      UUID sourceExperimentId,
                                      UUID sourceSnapshotId,
                                      MemoryPatch patch,
                                      TaskMemoryOrigin origin,
                                      TaskMemoryTrust trust,
                                      TaskMemoryStatus status,
                                      boolean requireTrustedEvidence) {
        Session session = session(sessionId);
        Experiment experiment = experiment(sourceExperimentId);
        Snapshot snapshot = snapshot(sourceSnapshotId);
        validateScope(session, experiment, snapshot);
        boolean belongsToExperiment = sourceSnapshotId.equals(experiment.baseSnapshotId())
                || sourceSnapshotId.equals(experiment.resultSnapshotId());
        if (!belongsToExperiment) {
            throw new DomainException("TASK_MEMORY_PROVENANCE_MISMATCH",
                    "Task memory source Snapshot is not bound to its source Experiment");
        }
        return appendValidated(session, experiment, snapshot, patch, origin, trust, status, requireTrustedEvidence);
    }

    private TaskMemoryRevision appendValidated(Session session,
                                               Experiment experiment,
                                               Snapshot snapshot,
                                               MemoryPatch patch,
                                               TaskMemoryOrigin origin,
                                               TaskMemoryTrust trust,
                                               TaskMemoryStatus status,
                                               boolean requireTrustedEvidence) {
        if (patch == null) throw new IllegalArgumentException("Memory patch must not be null");
        validateSuperseded(session, patch);
        validateEvidence(experiment, snapshot, patch.sourceEvidenceIds(), requireTrustedEvidence);

        UUID revisionId = UUID.randomUUID();
        for (int attempt = 1; attempt <= APPEND_ATTEMPTS; attempt++) {
            long sequence = memories.nextSequence(session.id());
            TaskMemoryRevision revision = new TaskMemoryRevision(revisionId, session.projectId(), session.id(),
                    experiment.id(), snapshot.id(), snapshot.fingerprint(), patch.kind(), patch.content(),
                    patch.sourceEvidenceIds(), origin, trust, status, patch.supersedesIds(), clock.now(), sequence);
            try {
                return memories.append(revision);
            } catch (DomainException error) {
                if (!"TASK_MEMORY_SEQUENCE_CONFLICT".equals(error.code()) || attempt == APPEND_ATTEMPTS) throw error;
            }
        }
        throw new IllegalStateException("Task memory append retry loop exited unexpectedly");
    }

    private void validateScope(Session session, Experiment experiment, Snapshot snapshot) {
        if (!experiment.sessionId().equals(session.id())
                || !experiment.projectId().equals(session.projectId())
                || !snapshot.projectId().equals(session.projectId())) {
            throw new DomainException("TASK_MEMORY_SCOPE_MISMATCH",
                    "Task memory provenance crossed a project or Session boundary");
        }
    }

    private void validateSuperseded(Session session, MemoryPatch patch) {
        for (UUID supersededId : patch.supersedesIds()) {
            TaskMemoryRevision superseded = memories.findById(supersededId)
                    .orElseThrow(() -> new NotFoundException("Task memory revision not found: " + supersededId));
            if (!superseded.projectId().equals(session.projectId())
                    || !superseded.sessionId().equals(session.id())) {
                throw new DomainException("TASK_MEMORY_SCOPE_MISMATCH",
                        "Task memory supersession crossed a project or Session boundary");
            }
            if (superseded.kind() != patch.kind()) {
                throw new DomainException("TASK_MEMORY_KIND_MISMATCH",
                        "A task memory revision can only supersede the same memory kind");
            }
        }
    }

    private void validateEvidence(Experiment experiment,
                                  Snapshot snapshot,
                                  List<UUID> requestedIds,
                                  boolean requireTrusted) {
        if (requireTrusted && requestedIds.isEmpty()) {
            throw new DomainException("TASK_MEMORY_EVIDENCE_REQUIRED",
                    "Verified system memory must cite at least one trusted Evidence record");
        }
        if (requestedIds.isEmpty()) return;
        List<Evidence> available = evidence.findByExperimentId(experiment.id());
        Set<UUID> seen = new HashSet<>();
        for (UUID requestedId : requestedIds) {
            Evidence item = available.stream().filter(candidate -> candidate.id().equals(requestedId))
                    .findFirst().orElseThrow(() -> new NotFoundException("Evidence not found: " + requestedId));
            if (!item.snapshotId().equals(snapshot.id())) {
                throw new DomainException("TASK_MEMORY_PROVENANCE_MISMATCH",
                        "Task memory Evidence is not bound to its source Snapshot");
            }
            if (requireTrusted && !item.trusted()) {
                throw new DomainException("TASK_MEMORY_UNTRUSTED_EVIDENCE",
                        "Verified system memory cannot cite Agent observation as trusted Evidence");
            }
            if (requireTrusted && (!item.kind().equals("VERIFICATION")
                    || item.exitCode() != 0
                    || item.timedOut()
                    || item.cancelled())) {
                throw new DomainException("TASK_MEMORY_INVALID_EVIDENCE",
                        "Verified system memory requires a successful, non-cancelled experiment verification Evidence record");
            }
            if (!seen.add(item.id())) {
                throw new DomainException("TASK_MEMORY_INVALID", "Task memory Evidence references must be unique");
            }
        }
    }

    private void rejectVerifiedFact(MemoryPatch patch, TaskMemoryOrigin origin) {
        if (patch != null && patch.kind() == TaskMemoryKind.VERIFIED_FACT) {
            throw new DomainException("TASK_MEMORY_UNTRUSTED_FACT",
                    origin + " cannot author a VERIFIED_FACT; trusted verification must establish it");
        }
    }

    private Session session(UUID id) {
        return sessions.findById(id).orElseThrow(() -> new NotFoundException("Session not found: " + id));
    }

    private Experiment experiment(UUID id) {
        return experiments.findById(id).orElseThrow(() -> new NotFoundException("Experiment not found: " + id));
    }

    private Snapshot snapshot(UUID id) {
        return snapshots.findById(id).orElseThrow(() -> new NotFoundException("Snapshot not found: " + id));
    }
}
