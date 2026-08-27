package com.pico.infrastructure.agent;

import com.pico.experiment.domain.Experiment;
import com.pico.shared.domain.DomainException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

@Component
public class WorkspacePathResolver {
    public Path resolve(Experiment experiment, String requestedPath, boolean write) {
        if (experiment.workspacePath() == null) {
            throw new DomainException("WORKSPACE_NOT_READY", "Experiment workspace is not ready");
        }
        if (requestedPath == null || requestedPath.isBlank()) {
            throw new DomainException("INVALID_PATH", "Path must not be blank");
        }
        Path workspace = experiment.workspacePath().toAbsolutePath().normalize();
        Path candidate = workspace.resolve(requestedPath).normalize();
        if (!candidate.startsWith(workspace)) {
            throw new DomainException("PATH_ESCAPE", "Path escapes experiment workspace");
        }
        try {
            Path existingParent = candidate;
            if (!Files.exists(existingParent, LinkOption.NOFOLLOW_LINKS)) {
                existingParent = candidate.getParent();
            }
            if (existingParent != null) {
                Path realParent = existingParent.toRealPath();
                if (!realParent.startsWith(workspace.toRealPath())) {
                    throw new DomainException("PATH_ESCAPE", "Resolved path escapes experiment workspace");
                }
            }
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(candidate)) {
                throw new DomainException("SYMLINK_NOT_ALLOWED", "Symlink targets are not writable through tools");
            }
        } catch (IOException error) {
            throw new DomainException("PATH_CHECK_FAILED", "Unable to resolve workspace path");
        }
        if (write && Files.exists(candidate) && !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new DomainException("NOT_A_FILE", "Write target is not a regular file: " + requestedPath);
        }
        return candidate;
    }
}
