package com.pico.infrastructure.memory;

import com.pico.port.ProjectRepository;
import com.pico.project.domain.Project;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("!mysql")
public class InMemoryProjectRepository implements ProjectRepository {
    private final ConcurrentHashMap<UUID, Project> projects = new ConcurrentHashMap<>();

    @Override
    public Project save(Project project) {
        projects.put(project.id(), project);
        return project;
    }

    @Override
    public Optional<Project> findById(UUID id) {
        return Optional.ofNullable(projects.get(id));
    }

    @Override
    public List<Project> findAll() {
        return projects.values().stream().sorted(Comparator.comparing(Project::createdAt)).toList();
    }
}
