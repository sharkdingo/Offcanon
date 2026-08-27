package com.pico.infrastructure.agent;

import com.pico.experiment.domain.Experiment;
import com.pico.shared.domain.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspacePathResolverTest {
    @TempDir
    Path workspace;

    @Test
    void allowsNewFilesBelowAnExistingWorkspace() {
        Experiment experiment = experiment();
        Path resolved = new WorkspacePathResolver().resolve(experiment, "src/main/App.java", true);

        assertEquals(workspace.resolve("src/main/App.java").toAbsolutePath().normalize(), resolved);
    }

    @Test
    void rejectsParentTraversal() {
        Experiment experiment = experiment();

        assertThrows(DomainException.class, () -> new WorkspacePathResolver().resolve(experiment, "../outside.txt", true));
    }

    @Test
    void rejectsProtectedPathsRegardlessOfCase() {
        Experiment experiment = experiment();

        assertThrows(DomainException.class, () -> new WorkspacePathResolver().resolve(experiment, ".GIT/config", true));
        assertThrows(DomainException.class, () -> new WorkspacePathResolver().resolve(experiment, ".PiCo/state", true));
        assertThrows(DomainException.class, () -> new WorkspacePathResolver().resolve(experiment, ".ENV", true));
    }

    private Experiment experiment() {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), workspace);
        return experiment;
    }
}
