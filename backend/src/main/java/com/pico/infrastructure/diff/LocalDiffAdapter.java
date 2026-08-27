package com.pico.infrastructure.diff;

import com.pico.port.DiffPort;
import com.pico.shared.domain.DomainException;
import com.pico.workspace.domain.Snapshot;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

@Component
public class LocalDiffAdapter implements DiffPort {
    private static final long MAX_FILE_BYTES = 2_000_000;
    private static final int MAX_ENTRIES = 1_000;

    @Override
    public List<DiffEntry> compare(Snapshot base, Path workspace) {
        Map<String, byte[]> before = files(base.materializedPath());
        Map<String, byte[]> after = files(workspace);
        Set<String> paths = new HashSet<>(before.keySet());
        paths.addAll(after.keySet());
        List<DiffEntry> result = new ArrayList<>();
        for (String path : paths.stream().sorted().toList()) {
            byte[] oldBytes = before.get(path);
            byte[] newBytes = after.get(path);
            if (Arrays.equals(oldBytes, newBytes)) continue;
            DiffEntry.Change change = oldBytes == null ? DiffEntry.Change.ADDED
                    : newBytes == null ? DiffEntry.Change.DELETED : DiffEntry.Change.MODIFIED;
            boolean binary = containsZero(oldBytes) || containsZero(newBytes);
            result.add(new DiffEntry(path, change, size(oldBytes), size(newBytes), binary));
            if (result.size() >= MAX_ENTRIES) break;
        }
        return List.copyOf(result);
    }

    private Map<String, byte[]> files(Path root) {
        Map<String, byte[]> result = new HashMap<>();
        try {
            if (root == null || !Files.isDirectory(root)) {
                throw new DomainException("DIFF_WORKSPACE_MISSING", "Diff workspace does not exist: " + root);
            }
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                    if (!directory.equals(root) && isProtected(root.relativize(directory).toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!directory.equals(root) && Files.isSymbolicLink(directory)) {
                        throw new DomainException("DIFF_SYMLINK_BLOCKED", "Symlink directory in experiment workspace");
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    if (isProtected(relative) || Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE;
                    if (Files.size(file) > MAX_FILE_BYTES) {
                        throw new DomainException("DIFF_FILE_TOO_LARGE", "Diff file exceeds safe preview limit: " + relative);
                    }
                    result.put(relative, Files.readAllBytes(file));
                    return FileVisitResult.CONTINUE;
                }
            });
            return result;
        } catch (IOException error) {
            throw new DomainException("DIFF_READ_FAILED", "Unable to read workspace diff");
        }
    }

    private boolean isProtected(String relative) {
        String[] parts = relative.replace('\\', '/').toLowerCase(Locale.ROOT).split("/");
        for (String part : parts) {
            if (part.equals(".git") || part.equals(".pico")) return true;
        }
        String file = parts.length == 0 ? relative : parts[parts.length - 1];
        return file.equals(".env") || file.startsWith(".env.");
    }

    private boolean containsZero(byte[] bytes) {
        if (bytes == null) return false;
        for (byte value : bytes) if (value == 0) return true;
        return false;
    }

    private long size(byte[] bytes) {
        return bytes == null ? 0 : bytes.length;
    }
}
