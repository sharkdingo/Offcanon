package com.offcanon.infrastructure.agent;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.shared.domain.SensitivePathPolicy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

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
        // The experiment root is a capability boundary.  Reject replacing the
        // root itself with a symlink before resolving any child path.
        if (Files.isSymbolicLink(workspace)
                || !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) {
            throw new DomainException("WORKSPACE_NOT_READY", "Experiment workspace is not a real directory");
        }
        Path candidate = workspace.resolve(requestedPath).normalize();
        if (!candidate.startsWith(workspace)) {
            throw new DomainException("PATH_ESCAPE", "Path escapes experiment workspace");
        }
        String relative = workspace.relativize(candidate).toString().replace('\\', '/');
        if (isProtectedPath(relative)) {
            throw new DomainException("PROTECTED_PATH", "Tool access is blocked for internal or sensitive path: " + relative);
        }
        try {
            Path existingParent = candidate;
            while (existingParent != null && !Files.exists(existingParent, LinkOption.NOFOLLOW_LINKS)) {
                existingParent = existingParent.getParent();
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

    private boolean isProtectedPath(String relative) {
        String[] parts = relative.toLowerCase(Locale.ROOT).split("/");
        for (String part : parts) {
            if (part.equals(".git") || part.equals(".offcanon")) return true;
        }
        return SensitivePathPolicy.isSensitiveRelativePath(relative);
    }
}
