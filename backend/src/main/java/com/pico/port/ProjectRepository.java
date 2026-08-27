package com.pico.port;

import com.pico.project.domain.Project;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository {
    Project save(Project project);
    Optional<Project> findById(UUID id);
    Optional<Project> findByCanonicalPath(Path canonicalPath);
    List<Project> findAll();
}
