package com.offcanon.application;

import com.offcanon.port.ProjectRepository;
import com.offcanon.port.ExperimentRepository;
import com.offcanon.port.EventSink;
import com.offcanon.port.PromotionLockPort;
import com.offcanon.port.PromotionJournalPort;
import com.offcanon.port.SnapshotPort;
import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.project.domain.Project;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.shared.web.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProjectApplicationService {
    private final ProjectRepository projectRepository;
    private final SnapshotPort snapshots;
    private final ExperimentRepository experiments;
    private final PromotionLockPort promotionLock;
    private final PromotionJournalPort promotionJournals;
    private final EventSink events;

    @Autowired
    public ProjectApplicationService(ProjectRepository projectRepository,
                                     SnapshotPort snapshots,
                                     ExperimentRepository experiments,
                                     PromotionLockPort promotionLock,
                                     EventSink events,
                                     PromotionJournalPort promotionJournals) {
        this.projectRepository = projectRepository;
        this.snapshots = snapshots;
        this.experiments = experiments;
        this.promotionLock = promotionLock;
        this.events = events;
        this.promotionJournals = promotionJournals;
    }

    /** Compatibility constructor for integrations that already provide events. */
    public ProjectApplicationService(ProjectRepository projectRepository,
                                     SnapshotPort snapshots,
                                     ExperimentRepository experiments,
                                     PromotionLockPort promotionLock,
                                     EventSink events) {
        this(projectRepository, snapshots, experiments, promotionLock, events, null);
    }

    /** Constructor for focused tests/integrations that model promotion journals. */
    public ProjectApplicationService(ProjectRepository projectRepository,
                                     SnapshotPort snapshots,
                                     ExperimentRepository experiments,
                                     PromotionLockPort promotionLock,
                                     PromotionJournalPort promotionJournals) {
        this(projectRepository, snapshots, experiments, promotionLock, NOOP_EVENTS, promotionJournals);
    }

    /** Compatibility constructor for focused tests that do not model events. */
    public ProjectApplicationService(ProjectRepository projectRepository,
                                     SnapshotPort snapshots,
                                     ExperimentRepository experiments,
                                     PromotionLockPort promotionLock) {
        this(projectRepository, snapshots, experiments, promotionLock, NOOP_EVENTS);
    }

    private static final EventSink NOOP_EVENTS = new EventSink() {
        @Override
        public com.offcanon.agent.domain.RunEvent publish(UUID experimentId, String type, Map<String, Object> payload) {
            return null;
        }

        @Override
        public List<com.offcanon.agent.domain.RunEvent> after(UUID experimentId, long sequence) {
            return List.of();
        }
    };

    public Project register(UUID ownerId, String name, String canonicalPath, List<String> verificationCommands) {
        return registerWithOutcome(ownerId, name, canonicalPath, verificationCommands).project();
    }

    /**
     * Registers a project or reopens the already registered project owned by
     * this account. The explicit outcome lets the HTTP layer explain a reopen
     * without making clients infer it from a possibly stale project list.
     */
    public RegistrationResult registerWithOutcome(UUID ownerId,
                                                   String name,
                                                   String canonicalPath,
                                                   List<String> verificationCommands) {
        if (ownerId == null) throw new DomainException("OWNER_REQUIRED", "Project owner is required");
        Path root = snapshots.resolveProjectRoot(Path.of(canonicalPath));
        var existing = projectRepository.findByCanonicalPath(root);
        if (existing.isPresent()) {
            return new RegistrationResult(reopenForOwner(root, ownerId, existing.get()), true);
        }
        // A repeated open reuses the stored policy, so validate commands only
        // after confirming this is a genuinely new project registration.
        List<String> policy = normalizeVerificationCommands(verificationCommands);
        try {
            Project created = projectRepository.save(Project.create(ownerId, name, root, policy, Instant.now()));
            return new RegistrationResult(created, false);
        } catch (DomainException error) {
            if (!"PROJECT_ALREADY_REGISTERED".equals(error.code())) throw error;
            return projectRepository.findByCanonicalPath(root)
                    .map(project -> new RegistrationResult(reopenForOwner(root, ownerId, project), true))
                    .orElseThrow(() -> duplicateProject(root));
        }
    }

    public List<Project> list(UUID ownerId) {
        return projectRepository.findAll().stream().filter(project -> project.ownerId().equals(ownerId)).toList();
    }

    public Project get(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));
    }

    public Project get(UUID projectId, UUID ownerId) {
        Project project = get(projectId);
        requireOwner(project, ownerId);
        return project;
    }

    /**
     * Updates display metadata and verification policy for an owned project.
     * The canonical path is intentionally immutable after registration.
     */
    @Transactional
    public Project update(UUID ownerId,
                          UUID projectId,
                          String name,
                          String canonicalPath,
                          List<String> verificationCommands) {
        return promotionLock.withProjectLock(projectId,
                () -> updateUnlocked(ownerId, projectId, name, canonicalPath, verificationCommands));
    }

    private Project updateUnlocked(UUID ownerId,
                                   UUID projectId,
                                   String name,
                                   String canonicalPath,
                                   List<String> verificationCommands) {
        if (ownerId == null) throw new DomainException("OWNER_REQUIRED", "Project owner is required");
        Project current = get(projectId, ownerId);
        Path resolved = snapshots.resolveProjectRoot(Path.of(canonicalPath == null ? "" : canonicalPath));
        if (!resolved.equals(current.canonicalPath())) {
            throw new DomainException("PROJECT_PATH_IMMUTABLE",
                    "A registered project's canonical path cannot be changed; open a new project instead");
        }
        List<String> policy = normalizeVerificationCommands(verificationCommands);
        // A sealed AGENT_COMPLETED result is explicitly a waiting state: the
        // user must be able to add, correct, or clear commands before asking
        // for verification. The agent boundary transitions policy-backed runs
        // to VERIFYING under the same project lock, so every other lifecycle
        // state remains protected by this blocking query.
        boolean policyChanged = !current.verificationCommands().equals(policy);
        boolean policyChangeBlocked = experiments.hasBlockingExperimentForProject(projectId);
        boolean promotionRecoveryPending = policyChanged
                && promotionJournals != null
                && !promotionJournals.findUnresolvedByProject(projectId).isEmpty();
        if (policyChanged && (policyChangeBlocked || promotionRecoveryPending)) {
            throw new DomainException("VERIFICATION_POLICY_LOCKED",
                    "Project verification commands cannot change while an experiment or promotion is active or unresolved");
        }
        // Validate the complete project representation before touching any
        // pending verification rows. This keeps an invalid display name or a
        // repository-level validation error from changing lifecycle state.
        Project candidate = current.updated(name, policy);
        List<Experiment> invalidated = policyChanged ? invalidateVerifiedResults(projectId) : List.of();
        try {
            Project updated = projectRepository.update(candidate);
            invalidated.forEach(this::publishPolicyInvalidation);
            return updated;
        } catch (DomainException error) {
            if ("PROJECT_VERSION_CONFLICT".equals(error.code())) {
                throw error;
            }
            throw error;
        }
    }

    /**
     * A passing result is bound to the policy that produced its evidence. Once
     * the policy changes, retaining VERIFIED would let an old result be
     * interpreted under the new commands. Return those pending results to the
     * sealed waiting boundary while keeping their immutable snapshots and
     * evidence available for audit; the same immutable result can be
     * re-verified under the new policy without asking the user to start over.
     */
    private List<Experiment> invalidateVerifiedResults(UUID projectId) {
        List<Experiment> invalidated = new java.util.ArrayList<>();
        for (Experiment experiment : experiments.findByProjectId(projectId)) {
            if (experiment.status() != ExperimentStatus.VERIFIED) continue;
            experiment.invalidateVerificationForPolicyChange();
            experiments.save(experiment);
            invalidated.add(experiment);
        }
        return List.copyOf(invalidated);
    }

    private void publishPolicyInvalidation(Experiment experiment) {
        try {
            events.publish(experiment.id(), "VERIFICATION_INVALIDATED", Map.of(
                    "status", experiment.status().name(),
                    "reason", "VERIFICATION_POLICY_CHANGED",
                    "resultSnapshotId", experiment.resultSnapshotId().toString()));
        } catch (RuntimeException ignored) {
            // Settings and lifecycle state remain authoritative if telemetry is unavailable.
        }
    }

    private List<String> normalizeVerificationCommands(List<String> verificationCommands) {
        List<String> policy = verificationCommands == null ? List.of() : verificationCommands.stream()
                .map(command -> command == null ? "" : command.trim())
                .filter(command -> !command.isBlank()).toList();
        for (String command : policy) {
            if (command.length() > 1_000) {
                throw new DomainException("VERIFICATION_COMMAND_TOO_LARGE",
                        "Each verification command cannot exceed 1000 characters");
            }
        }
        if (policy.size() > 20) {
            throw new DomainException("VERIFICATION_POLICY_TOO_LARGE",
                    "Configure no more than 20 verification commands");
        }
        return policy;
    }

    private void requireOwner(Project project, UUID ownerId) {
        if (ownerId == null || !project.ownerId().equals(ownerId)) {
            throw new com.offcanon.shared.web.ForbiddenException("You do not own this project");
        }
    }

    private DomainException duplicateProject(Path canonicalPath, Project existing) {
        return duplicateProject(canonicalPath);
    }

    private DomainException duplicateProject(Path canonicalPath) {
        // A repository may belong to another local account.  Do not expose its
        // internal project id or canonical path in an API error: the caller
        // only needs an actionable ownership explanation, and the path can be
        // sensitive on a shared machine.
        return new DomainException("PROJECT_ALREADY_REGISTERED",
                "This Git repository is already registered by another account. "
                        + "Use that account or choose a different repository.");
    }

    private Project reopenForOwner(Path canonicalPath, UUID ownerId, Project existing) {
        if (existing.ownerId().equals(ownerId)) return existing;
        throw duplicateProject(canonicalPath, existing);
    }

    public record RegistrationResult(Project project, boolean reopened) {
    }
}
