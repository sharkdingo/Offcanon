package com.offcanon.infrastructure.memory;

import com.offcanon.port.ProjectRepository;
import com.offcanon.project.domain.CanonicalPathIdentity;
import com.offcanon.project.domain.Project;
import com.offcanon.shared.domain.DomainException;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("!mysql")
public class InMemoryProjectRepository implements ProjectRepository {
    private final ConcurrentHashMap<UUID, Project> projects = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> canonicalOwners = new ConcurrentHashMap<>();

    @Override
    public synchronized Project save(Project project) {
        String key = CanonicalPathIdentity.value(project.canonicalPath());
        UUID owner = canonicalOwners.get(key);
        if (owner != null && !owner.equals(project.id())) {
            throw duplicate(project, owner);
        }
        Project previous = projects.get(project.id());
        if (previous != null) {
            String previousKey = CanonicalPathIdentity.value(previous.canonicalPath());
            if (!previousKey.equals(key)) canonicalOwners.remove(previousKey, project.id());
        }
        projects.put(project.id(), project);
        canonicalOwners.put(key, project.id());
        return project;
    }

    @Override
    public Optional<Project> findById(UUID id) {
        return Optional.ofNullable(projects.get(id));
    }

    @Override
    public Optional<Project> findByCanonicalPath(Path canonicalPath) {
        UUID id = canonicalOwners.get(CanonicalPathIdentity.value(canonicalPath));
        return id == null ? Optional.empty() : Optional.ofNullable(projects.get(id));
    }

    @Override
    public List<Project> findAll() {
        return projects.values().stream().sorted(Comparator.comparing(Project::createdAt)).toList();
    }

    private DomainException duplicate(Project project, UUID owner) {
        return new DomainException("PROJECT_ALREADY_REGISTERED",
                "Canonical Git repository is already registered as project " + owner + ": " + project.canonicalPath());
    }
}
