package com.pico.infrastructure.workspace;

import com.pico.experiment.domain.Experiment;
import com.pico.port.WorkspacePort;
import com.pico.shared.domain.DomainException;
import com.pico.workspace.domain.Snapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

@Component
public class LocalWorkspaceAdapter implements WorkspacePort {
    private final Path dataRoot;

    public LocalWorkspaceAdapter(@Value("${pico.data-root}") String dataRoot) {
        this.dataRoot = Path.of(dataRoot).toAbsolutePath().normalize();
    }

    @Override
    public Path materialize(Snapshot snapshot, UUID experimentId) {
        Path destination = dataRoot.resolve("experiments").resolve(experimentId.toString()).toAbsolutePath().normalize();
        copyTree(snapshot.materializedPath(), destination);
        return destination;
    }

    @Override
    public Path createVerificationWorkspace(Snapshot result, Experiment experiment) {
        Path destination = dataRoot.resolve("verification-workspaces").resolve(experiment.id().toString())
                .resolve("attempt-" + UUID.randomUUID())
                .toAbsolutePath().normalize();
        copyTree(result.materializedPath(), destination);
        return destination;
    }

    @Override
    public Path createPromotionCandidate(Snapshot result, Experiment experiment) {
        Path destination = dataRoot.resolve("promotion-candidates").resolve(experiment.id().toString())
                .resolve("attempt-" + UUID.randomUUID())
                .toAbsolutePath().normalize();
        copyTree(result.materializedPath(), destination);
        return destination;
    }

    private void copyTree(Path source, Path destination) {
        try {
            if (!Files.isDirectory(source)) {
                throw new DomainException("WORKSPACE_SOURCE_MISSING", "Workspace source does not exist: " + source);
            }
            if (Files.exists(destination)) {
                throw new DomainException("WORKSPACE_ALREADY_EXISTS", "Workspace already exists: " + destination);
            }
            Files.createDirectories(destination);
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(source) && Files.isSymbolicLink(dir)) {
                        throw new DomainException("WORKSPACE_SYMLINK_BLOCKED", "Symlink directory cannot be materialized: " + source.relativize(dir));
                    }
                    Path relative = source.relativize(dir);
                    Files.createDirectories(destination.resolve(relative));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(file)) {
                        throw new DomainException("WORKSPACE_SYMLINK_BLOCKED", "Symlink cannot be materialized: " + source.relativize(file));
                    }
                    Path relative = source.relativize(file);
                    Files.copy(file, destination.resolve(relative), StandardCopyOption.COPY_ATTRIBUTES);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new DomainException("WORKSPACE_CREATE_FAILED", e.getMessage() == null ? "Unable to materialize workspace" : e.getMessage());
        }
    }
}
