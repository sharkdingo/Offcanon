package com.pico.application;

import com.pico.port.ProjectRepository;
import com.pico.project.domain.Project;
import com.pico.shared.web.NotFoundException;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ProjectApplicationService {
    private final ProjectRepository projectRepository;

    public ProjectApplicationService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    public Project register(String name, String canonicalPath, List<String> verificationCommands) {
        return projectRepository.save(Project.create(name, Path.of(canonicalPath), verificationCommands, Instant.now()));
    }

    public List<Project> list() {
        return projectRepository.findAll();
    }

    public Project get(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));
    }
}
