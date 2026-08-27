package com.offcanon.application;

import com.offcanon.port.ProjectRepository;
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

    public ProjectApplicationService(ProjectRepository projectRepository, SnapshotPort snapshots) {
        this.projectRepository = projectRepository;
        this.snapshots = snapshots;
    }

    public Project register(String name, String canonicalPath, List<String> verificationCommands) {
        return register(Project.LEGACY_OWNER_ID, name, canonicalPath, verificationCommands);
    }

    public Project register(UUID ownerId, String name, String canonicalPath, List<String> verificationCommands) {
        if (ownerId == null) throw new DomainException("OWNER_REQUIRED", "Project owner is required");
        List<String> policy = verificationCommands == null ? List.of() : verificationCommands.stream()
                .map(String::trim).filter(command -> !command.isBlank()).toList();
        if (policy.isEmpty()) {
            throw new DomainException("VERIFICATION_POLICY_MISSING",
                    "Configure at least one verification command before registering a project");
        }
        Path root = snapshots.resolveProjectRoot(Path.of(canonicalPath));
        projectRepository.findByCanonicalPath(root).ifPresent(existing -> {
            throw duplicateProject(root, existing);
        });
        return projectRepository.save(Project.create(ownerId, name, root, policy, Instant.now()));
    }

    public List<Project> list() {
        return projectRepository.findAll();
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

    private void requireOwner(Project project, UUID ownerId) {
        if (ownerId == null || !project.ownerId().equals(ownerId)) {
            throw new com.offcanon.shared.web.ForbiddenException("You do not own this project");
        }
    }

    private DomainException duplicateProject(Path canonicalPath, Project existing) {
        return new DomainException("PROJECT_ALREADY_REGISTERED",
                "Canonical Git repository is already registered as project " + existing.id() + ": " + canonicalPath);
    }
}
