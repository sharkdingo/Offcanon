package com.offcanon.application;

import com.offcanon.port.ProjectRepository;
import com.offcanon.port.ExperimentRepository;
import com.offcanon.port.PromotionLockPort;
import com.offcanon.port.SnapshotPort;
import com.offcanon.project.domain.Project;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.shared.web.NotFoundException;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ProjectApplicationService {
    private final ProjectRepository projectRepository;
    private final SnapshotPort snapshots;
    private final ExperimentRepository experiments;
    private final PromotionLockPort promotionLock;

    public ProjectApplicationService(ProjectRepository projectRepository, SnapshotPort snapshots) {
        this(projectRepository, snapshots, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProjectApplicationService(ProjectRepository projectRepository,
                                     SnapshotPort snapshots,
                                     ExperimentRepository experiments,
                                     PromotionLockPort promotionLock) {
        this.projectRepository = projectRepository;
        this.snapshots = snapshots;
        this.experiments = experiments;
        this.promotionLock = promotionLock;
    }

    /** Compatibility constructor for embedded callers that do not need locks. */
    public ProjectApplicationService(ProjectRepository projectRepository,
                                     SnapshotPort snapshots,
                                     ExperimentRepository experiments) {
        this(projectRepository, snapshots, experiments, null);
    }

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
    public Project update(UUID ownerId,
                          UUID projectId,
                          String name,
                          String canonicalPath,
                          List<String> verificationCommands) {
        if (promotionLock == null) {
            return updateUnlocked(ownerId, projectId, name, canonicalPath, verificationCommands);
        }
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
        if (!current.verificationCommands().equals(policy)
                && experiments != null
                && experiments.hasActiveExperimentForProject(projectId)) {
            throw new DomainException("VERIFICATION_POLICY_LOCKED",
                    "Project verification commands cannot change while an experiment or promotion is active");
        }
        try {
            return projectRepository.update(current.updated(name, policy));
        } catch (DomainException error) {
            if ("PROJECT_VERSION_CONFLICT".equals(error.code())) {
                throw error;
            }
            throw error;
        }
    }

    private List<String> normalizeVerificationCommands(List<String> verificationCommands) {
        List<String> policy = verificationCommands == null ? List.of() : verificationCommands.stream()
                .map(command -> command == null ? "" : command.trim())
                .filter(command -> !command.isBlank()).toList();
        if (policy.isEmpty()) {
            throw new DomainException("VERIFICATION_POLICY_MISSING",
                    "Configure at least one verification command before registering a project");
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
