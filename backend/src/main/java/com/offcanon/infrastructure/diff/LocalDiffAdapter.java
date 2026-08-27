package com.offcanon.infrastructure.diff;

import com.offcanon.port.DiffPort;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.workspace.domain.Snapshot;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
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
    private static final int MAX_PATCH_LINES = 2_000;
    private static final int MAX_LCS_LINES = 1_000;

    @Override
    public List<DiffEntry> compare(Snapshot base, Path workspace) {
        Map<String, Path> before = files(base.materializedPath());
        Map<String, Path> after = files(workspace);
        Set<String> paths = new HashSet<>(before.keySet());
        paths.addAll(after.keySet());
        List<DiffEntry> result = new ArrayList<>();
        for (String path : paths.stream().sorted().toList()) {
            Path oldFile = before.get(path);
            Path newFile = after.get(path);
            if (sameContent(oldFile, newFile)) continue;
            DiffEntry.Change change = oldFile == null ? DiffEntry.Change.ADDED
                    : newFile == null ? DiffEntry.Change.DELETED : DiffEntry.Change.MODIFIED;
            BoundedRead oldRead = readBounded(oldFile);
            BoundedRead newRead = readBounded(newFile);
            long oldSize = oldRead.size();
            long newSize = newRead.size();
            if (oldRead.exceeded() || newRead.exceeded()) {
                result.add(new DiffEntry(path, change, oldSize, newSize, true,
                        0, 0, "File differs but exceeds the 2 MB preview limit"));
                if (result.size() >= MAX_ENTRIES) break;
                continue;
            }
            if (oldRead.unstable() || newRead.unstable()) {
                result.add(new DiffEntry(path, change, oldSize, newSize, true,
                        0, 0, "File changed while being read; preview is unavailable"));
                if (result.size() >= MAX_ENTRIES) break;
                continue;
            }
            byte[] oldBytes = oldRead.bytes();
            byte[] newBytes = newRead.bytes();
            if (Arrays.equals(oldBytes, newBytes)) continue;
            boolean binary = !validText(oldBytes) || !validText(newBytes);
            TextDiff textDiff = binary ? new TextDiff(0, 0, "Binary files differ") : textDiff(oldBytes, newBytes, change);
            result.add(new DiffEntry(path, change, oldSize, newSize, binary,
                    textDiff.additions(), textDiff.deletions(), textDiff.patch()));
            if (result.size() >= MAX_ENTRIES) break;
        }
        return List.copyOf(result);
    }

    private Map<String, Path> files(Path root) {
        Map<String, Path> result = new HashMap<>();
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
                    result.put(relative, file);
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
            if (part.equals(".git") || part.equals(".offcanon") || part.equals("node_modules")
                    || part.equals("target") || part.equals("build") || part.equals("dist")
                    || part.equals(".idea") || part.equals(".vscode")) return true;
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

    private boolean sameContent(Path oldFile, Path newFile) {
        if (oldFile == null || newFile == null) return false;
        try {
            return Files.size(oldFile) == Files.size(newFile) && Files.mismatch(oldFile, newFile) == -1;
        } catch (IOException error) {
            throw new DomainException("DIFF_READ_FAILED", "Unable to compare workspace files");
        }
    }

    private BoundedRead readBounded(Path file) {
        if (file == null) return new BoundedRead(null, 0, false, false);
        try {
            BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (before.isSymbolicLink()) {
                throw new DomainException("DIFF_SYMLINK_BLOCKED", "Symlink file in experiment workspace");
            }
            if (before.size() > MAX_FILE_BYTES) {
                return new BoundedRead(null, before.size(), true, false);
            }
            byte[] bytes;
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes((int) MAX_FILE_BYTES + 1);
            }
            BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            boolean exceeded = bytes.length > MAX_FILE_BYTES || after.size() > MAX_FILE_BYTES;
            boolean unstable = before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !java.util.Objects.equals(before.fileKey(), after.fileKey())
                    || bytes.length != after.size();
            return new BoundedRead(exceeded || unstable ? null : bytes, after.size(), exceeded, unstable);
        } catch (IOException error) {
            throw new DomainException("DIFF_READ_FAILED", "Unable to read workspace file");
        }
    }

    private TextDiff textDiff(byte[] oldBytes, byte[] newBytes, DiffEntry.Change change) {
        try {
            ParsedLines oldLines = lines(oldBytes);
            ParsedLines newLines = lines(newBytes);
            if (change == DiffEntry.Change.ADDED) {
                return new TextDiff(newLines.count(), 0, patch(oldLines, newLines));
            }
            if (change == DiffEntry.Change.DELETED) {
                return new TextDiff(0, oldLines.count(), patch(oldLines, newLines));
            }
            long cells = (long) oldLines.count() * newLines.count();
            if (oldLines.count() > MAX_LCS_LINES || newLines.count() > MAX_LCS_LINES || cells > 1_000_000L) {
                return new TextDiff(newLines.count(), oldLines.count(), patch(oldLines, newLines));
            }
            return modifiedDiff(oldLines.preview(), newLines.preview());
        } catch (CharacterCodingException error) {
            return new TextDiff(0, 0, "Binary or invalid UTF-8 files differ");
        }
    }

    private TextDiff modifiedDiff(List<String> oldLines, List<String> newLines) {
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

    private ParsedLines lines(byte[] bytes) throws CharacterCodingException {
        if (bytes == null || bytes.length == 0) return new ParsedLines(0, List.of());
        String value = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        List<String> preview = new ArrayList<>(Math.min(MAX_PATCH_LINES, value.length()));
        int count = 0;
        int lineStart = 0;
        int index = 0;
        while (index < value.length()) {
            int separatorLength = lineSeparatorLength(value, index);
            if (separatorLength == 0) {
                index++;
                continue;
            }
            if (preview.size() < MAX_PATCH_LINES) preview.add(value.substring(lineStart, index));
            count++;
            index += separatorLength;
            lineStart = index;
        }
        if (lineStart < value.length()) {
            if (preview.size() < MAX_PATCH_LINES) preview.add(value.substring(lineStart));
            count++;
        }
        return new ParsedLines(count, List.copyOf(preview));
    }

    private int lineSeparatorLength(String value, int index) {
        char current = value.charAt(index);
        if (current == '\r') {
            return index + 1 < value.length() && value.charAt(index + 1) == '\n' ? 2 : 1;
        }
        return current == '\n' || current == '\u000B' || current == '\f' || current == '\u0085'
                || current == '\u2028' || current == '\u2029' ? 1 : 0;
    }

    private String patch(ParsedLines oldLines, ParsedLines newLines) {
        StringBuilder patch = new StringBuilder("--- base\n+++ result\n@@\n");
        int emitted = 0;
        for (String line : oldLines.preview()) {
            if (emitted >= MAX_PATCH_LINES) break;
            patch.append('-').append(line).append('\n');
            emitted++;
        }
        for (String line : newLines.preview()) {
            if (emitted >= MAX_PATCH_LINES) break;
            patch.append('+').append(line).append('\n');
            emitted++;
        }
        if ((long) oldLines.count() + newLines.count() > emitted) {
            patch.append("... [diff truncated]\n");
        }
        return patch.toString();
    }

    private String formatPatch(List<String> lines) {
        StringBuilder patch = new StringBuilder("--- base\n+++ result\n@@\n");
        int limit = Math.min(lines.size(), MAX_PATCH_LINES);
        for (int index = 0; index < limit; index++) patch.append(lines.get(index)).append('\n');
        if (limit < lines.size()) patch.append("... [diff truncated]\n");
        return patch.toString();
    }

    private record TextDiff(int additions, int deletions, String patch) {
    }

    private record ParsedLines(int count, List<String> preview) {
    }

    private record BoundedRead(byte[] bytes, long size, boolean exceeded, boolean unstable) {
    }
}
