package com.offcanon.port;

import com.offcanon.project.domain.Project;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository {
    Project save(Project project);
    /** Persist metadata changes for an existing project using its version. */
    Project update(Project project);
    Optional<Project> findById(UUID id);
    Optional<Project> findByCanonicalPath(Path canonicalPath);
    List<Project> findAll();
}
