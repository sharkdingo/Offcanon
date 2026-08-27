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
import java.nio.charset.CharacterCodingException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

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
            boolean binary = !validText(oldBytes) || !validText(newBytes);
            TextDiff textDiff = binary ? new TextDiff(0, 0, "Binary files differ") : textDiff(oldBytes, newBytes, change);
            result.add(new DiffEntry(path, change, size(oldBytes), size(newBytes), binary,
                    textDiff.additions(), textDiff.deletions(), textDiff.patch()));
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

    private boolean validText(byte[] bytes) {
        if (bytes == null) return true;
        if (containsZero(bytes)) return false;
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException error) {
            return false;
        }
    }

    private long size(byte[] bytes) {
        return bytes == null ? 0 : bytes.length;
    }

    private TextDiff textDiff(byte[] oldBytes, byte[] newBytes, DiffEntry.Change change) {
        try {
            List<String> oldLines = lines(oldBytes);
            List<String> newLines = lines(newBytes);
            if (change == DiffEntry.Change.ADDED) return new TextDiff(newLines.size(), 0, patch(List.of(), newLines));
            if (change == DiffEntry.Change.DELETED) return new TextDiff(0, oldLines.size(), patch(oldLines, List.of()));
            return modifiedDiff(oldLines, newLines);
        } catch (CharacterCodingException error) {
            return new TextDiff(0, 0, "Binary or invalid UTF-8 files differ");
        }
    }

    private TextDiff modifiedDiff(List<String> oldLines, List<String> newLines) {
        long cells = (long) oldLines.size() * newLines.size();
        if (cells > 1_000_000L) {
            return new TextDiff(newLines.size(), oldLines.size(), patch(oldLines, newLines));
        }
        int[][] lcs = new int[oldLines.size() + 1][newLines.size() + 1];
        for (int oldIndex = oldLines.size() - 1; oldIndex >= 0; oldIndex--) {
            for (int newIndex = newLines.size() - 1; newIndex >= 0; newIndex--) {
                lcs[oldIndex][newIndex] = oldLines.get(oldIndex).equals(newLines.get(newIndex))
                        ? lcs[oldIndex + 1][newIndex + 1] + 1
                        : Math.max(lcs[oldIndex + 1][newIndex], lcs[oldIndex][newIndex + 1]);
            }
        }
        List<String> patchLines = new ArrayList<>();
        int additions = 0;
        int deletions = 0;
        int oldIndex = 0;
        int newIndex = 0;
        while (oldIndex < oldLines.size() && newIndex < newLines.size()) {
            if (oldLines.get(oldIndex).equals(newLines.get(newIndex))) {
                patchLines.add(" " + oldLines.get(oldIndex++));
                newIndex++;
            } else if (lcs[oldIndex + 1][newIndex] >= lcs[oldIndex][newIndex + 1]) {
                patchLines.add("-" + oldLines.get(oldIndex++));
                deletions++;
            } else {
                patchLines.add("+" + newLines.get(newIndex++));
                additions++;
            }
        }
        while (oldIndex < oldLines.size()) { patchLines.add("-" + oldLines.get(oldIndex++)); deletions++; }
        while (newIndex < newLines.size()) { patchLines.add("+" + newLines.get(newIndex++)); additions++; }
        return new TextDiff(additions, deletions, formatPatch(patchLines));
    }

    private List<String> lines(byte[] bytes) throws CharacterCodingException {
        if (bytes == null || bytes.length == 0) return List.of();
        String value = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        String[] split = value.split("\\R", -1);
        if (split.length > 1 && split[split.length - 1].isEmpty()) split = java.util.Arrays.copyOf(split, split.length - 1);
        return List.of(split);
    }

    private String patch(List<String> oldLines, List<String> newLines) {
        List<String> lines = new ArrayList<>();
        oldLines.forEach(line -> lines.add("-" + line));
        newLines.forEach(line -> lines.add("+" + line));
        return formatPatch(lines);
    }

    private String formatPatch(List<String> lines) {
        StringBuilder patch = new StringBuilder("--- base\n+++ result\n@@\n");
        int limit = Math.min(lines.size(), 2000);
        for (int index = 0; index < limit; index++) patch.append(lines.get(index)).append('\n');
        if (limit < lines.size()) patch.append("... [diff truncated]\n");
        return patch.toString();
    }

    private record TextDiff(int additions, int deletions, String patch) {
    }
}
