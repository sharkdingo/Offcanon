package com.pico.application;

import com.pico.port.ProjectRepository;
import com.pico.port.SnapshotPort;
import com.pico.project.domain.Project;
import com.pico.shared.domain.DomainException;
import com.pico.shared.web.NotFoundException;
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
        return projectRepository.save(Project.create(name, root, policy, Instant.now()));
    }

    public List<Project> list() {
        return projectRepository.findAll();
    }

    public Project get(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));
    }

    private DomainException duplicateProject(Path canonicalPath, Project existing) {
        return new DomainException("PROJECT_ALREADY_REGISTERED",
                "Canonical Git repository is already registered as project " + existing.id() + ": " + canonicalPath);
    }
}
