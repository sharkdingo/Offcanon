package com.offcanon.infrastructure.diff;

import com.offcanon.infrastructure.filesystem.GitFileMode;
import com.offcanon.port.DiffPort;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.shared.domain.SensitivePathPolicy;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.nio.charset.CharacterCodingException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

@Component
public class LocalDiffAdapter implements DiffPort {
    private static final long MAX_FILE_BYTES = 2_000_000;
    private static final int MAX_ENTRIES = 1_000;
    /**
     * Bound the discovery walk independently from the response entry limit.
     * A repository can contain many unchanged files, so using MAX_ENTRIES as
     * the walk limit would make ordinary large projects impossible to review;
     * this higher ceiling still prevents an unbounded tree from exhausting
     * the diff request.
     */
    private static final int MAX_WALK_NODES = 100_000;
    private static final int MAX_PATCH_LINES = 2_000;
    private static final int MAX_PATCH_BYTES = 256 * 1024;
    private static final int MAX_TOTAL_PATCH_BYTES = 4 * 1024 * 1024;
    private static final int MAX_LCS_LINES = 1_000;
    private static final String PATCH_TRUNCATED = "... [diff truncated]\n";
    private static final String PATCH_OMITTED = "... [diff preview omitted: response limit reached]\n";
    private final int maxWalkNodes;

    public LocalDiffAdapter() {
        this(MAX_WALK_NODES);
    }

    LocalDiffAdapter(int maxWalkNodes) {
        this.maxWalkNodes = Math.max(1, maxWalkNodes);
    }

    @Override
    public List<DiffEntry> compare(Snapshot base, Path workspace) {
        Map<String, FileEntry> before = files(base.materializedPath());
        Map<String, FileEntry> after = files(workspace);
        Set<String> paths = new HashSet<>(before.keySet());
        paths.addAll(after.keySet());
        List<DiffEntry> result = new ArrayList<>();
        PatchBudget patchBudget = new PatchBudget();
        for (String path : paths.stream().sorted().toList()) {
            FileEntry oldFile = before.get(path);
            FileEntry newFile = after.get(path);
            boolean modeChanged = modeChanged(oldFile, newFile);
            if (!modeChanged && sameContent(pathOf(oldFile), pathOf(newFile))) continue;
            DiffEntry.Change change = oldFile == null ? DiffEntry.Change.ADDED
                    : newFile == null ? DiffEntry.Change.DELETED : DiffEntry.Change.MODIFIED;
            BoundedRead oldRead = readBounded(pathOf(oldFile));
            BoundedRead newRead = readBounded(pathOf(newFile));
            long oldSize = oldRead.size();
            long newSize = newRead.size();
            if (oldRead.exceeded() || newRead.exceeded()) {
                addEntry(result, new DiffEntry(path, change, oldSize, newSize, true,
                        0, 0, decoratePatch("File differs but exceeds the 2 MB preview limit", oldFile, newFile)), patchBudget);
                continue;
            }
            if (oldRead.unstable() || newRead.unstable()) {
                addEntry(result, new DiffEntry(path, change, oldSize, newSize, true,
                        0, 0, decoratePatch("File changed while being read; preview is unavailable", oldFile, newFile)), patchBudget);
                continue;
            }
            byte[] oldBytes = oldRead.bytes();
            byte[] newBytes = newRead.bytes();
            if (Arrays.equals(oldBytes, newBytes)) {
                if (!modeChanged) continue;
                addEntry(result, new DiffEntry(path, DiffEntry.Change.MODIFIED, oldSize, newSize, false,
                        0, 0, decoratePatch("", oldFile, newFile)), patchBudget);
                continue;
            }
            boolean binary = !validText(oldBytes) || !validText(newBytes);
            TextDiff textDiff = binary ? new TextDiff(0, 0, "Binary files differ") : textDiff(oldBytes, newBytes, change);
            addEntry(result, new DiffEntry(path, change, oldSize, newSize, binary,
                    textDiff.additions(), textDiff.deletions(), decoratePatch(textDiff.patch(), oldFile, newFile)), patchBudget);
        }
        return List.copyOf(result);
    }

    private void addEntry(List<DiffEntry> result, DiffEntry entry, PatchBudget patchBudget) {
        if (result.size() >= MAX_ENTRIES) {
            throw new DomainException("DIFF_TOO_LARGE",
                    "Diff contains more than " + MAX_ENTRIES + " changed files");
        }
        result.add(new DiffEntry(entry.path(), entry.change(), entry.beforeBytes(), entry.afterBytes(), entry.binary(),
                entry.additions(), entry.deletions(), patchBudget.take(entry.patch())));
    }

    private Map<String, FileEntry> files(Path root) {
        Map<String, FileEntry> result = new HashMap<>();
        int[] visitedNodes = {0};
        try {
            // The root itself is a capability boundary.  Files.isDirectory
            // follows links by default; accepting a swapped root symlink would
            // make an otherwise owner-scoped diff walk an unrelated tree.
            if (root == null || Files.isSymbolicLink(root)
                    || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new DomainException("DIFF_WORKSPACE_MISSING", "Diff workspace does not exist: " + root);
            }
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                    if (++visitedNodes[0] > maxWalkNodes) {
                        throw new DomainException("DIFF_TOO_LARGE",
                                "Diff workspace contains too many filesystem entries");
                    }
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
                    if (++visitedNodes[0] > maxWalkNodes) {
                        throw new DomainException("DIFF_TOO_LARGE",
                                "Diff workspace contains too many filesystem entries");
                    }
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    if (isProtected(relative)) return FileVisitResult.CONTINUE;
                    if (Files.isSymbolicLink(file)) {
                        throw new DomainException("DIFF_SYMLINK_BLOCKED", "Symlink in experiment workspace: " + relative);
                    }
                    result.put(relative, new FileEntry(file, GitFileMode.read(file)));
                    return FileVisitResult.CONTINUE;
                }
            });
            return result;
        } catch (IOException error) {
            throw new DomainException("DIFF_READ_FAILED", "Unable to read workspace diff");
        }
    }

    private Path pathOf(FileEntry file) {
        return file == null ? null : file.path();
    }

    private boolean modeChanged(FileEntry before, FileEntry after) {
        if (before == null) return after != null && after.mode() != GitFileMode.REGULAR;
        if (after == null) return before.mode() != GitFileMode.REGULAR;
        return before.mode() != after.mode();
    }

    private String decoratePatch(String contentPatch, FileEntry before, FileEntry after) {
        if (!modeChanged(before, after)) return contentPatch;
        PatchBuilder patch = new PatchBuilder();
        if (before == null) {
            patch.append("new mode ").append(modeText(after.mode())).append('\n');
        } else if (after == null) {
            patch.append("deleted mode ").append(modeText(before.mode())).append('\n');
        } else {
            patch.append("old mode ").append(modeText(before.mode())).append('\n')
                    .append("new mode ").append(modeText(after.mode())).append('\n');
        }
        if (contentPatch != null && !contentPatch.isBlank()) patch.append(contentPatch);
        return patch.build();
    }

    private String modeText(int mode) {
        return Integer.toOctalString(mode);
    }

    private boolean isProtected(String relative) {
        String[] parts = relative.replace('\\', '/').toLowerCase(Locale.ROOT).split("/");
        for (String part : parts) {
            if (part.equals(".git") || part.equals(".offcanon") || part.equals("node_modules")
                    || part.equals("target") || part.equals("build") || part.equals("dist")
                    || part.equals(".idea") || part.equals(".vscode")) return true;
        }
        return SensitivePathPolicy.isSensitiveRelativePath(relative);
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
            BasicFileAttributes oldAttributes = Files.readAttributes(oldFile, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes newAttributes = Files.readAttributes(newFile, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (oldAttributes.isSymbolicLink() || newAttributes.isSymbolicLink()) {
                throw new DomainException("DIFF_SYMLINK_BLOCKED", "Symlink appeared while comparing workspace files");
            }
            return oldAttributes.size() == newAttributes.size() && Files.mismatch(oldFile, newFile) == -1;
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
        PatchBuilder patch = new PatchBuilder();
        patch.append("--- base\n+++ result\n@@\n");
        int emitted = 0;
        for (String line : oldLines.preview()) {
            if (emitted >= MAX_PATCH_LINES) break;
            if (!appendPatchLine(patch, '-', line)) break;
            emitted++;
        }
        for (String line : newLines.preview()) {
            if (patch.truncated()) break;
            if (emitted >= MAX_PATCH_LINES) break;
            if (!appendPatchLine(patch, '+', line)) break;
            emitted++;
        }
        if ((long) oldLines.count() + newLines.count() > emitted) {
            patch.markTruncated();
        }
        return patch.build();
    }

    private String formatPatch(List<String> lines) {
        PatchBuilder patch = new PatchBuilder();
        patch.append("--- base\n+++ result\n@@\n");
        int limit = Math.min(lines.size(), MAX_PATCH_LINES);
        int emitted = 0;
        for (int index = 0; index < limit; index++) {
            if (!appendPatchLine(patch, lines.get(index))) break;
            emitted++;
        }
        if (emitted < lines.size()) patch.markTruncated();
        return patch.build();
    }

    private boolean appendPatchLine(PatchBuilder patch, char prefix, String line) {
        patch.append(prefix).append(line).append('\n');
        return !patch.truncated();
    }

    private boolean appendPatchLine(PatchBuilder patch, String line) {
        patch.append(line).append('\n');
        return !patch.truncated();
    }

    private static int utf8Length(int codePoint) {
        if (codePoint <= 0x7f) return 1;
        if (codePoint <= 0x7ff) return 2;
        return codePoint <= 0xffff ? 3 : 4;
    }

    private record TextDiff(int additions, int deletions, String patch) {
    }

    private record ParsedLines(int count, List<String> preview) {
    }

    private record BoundedRead(byte[] bytes, long size, boolean exceeded, boolean unstable) {
    }

    private record FileEntry(Path path, int mode) {
    }

    /** Bounds previews while appending so a single unbroken line cannot create a multi-megabyte patch. */
    private static final class PatchBuilder {
        private final int maxBytes;
        private final int contentByteLimit;

        private final StringBuilder value;
        private int byteCount;
        private boolean truncated;

        private PatchBuilder() {
            this(MAX_PATCH_BYTES);
        }

        private PatchBuilder(int maxBytes) {
            this.maxBytes = Math.max(0, maxBytes);
            int markerBytes = PATCH_TRUNCATED.getBytes(StandardCharsets.UTF_8).length;
            this.contentByteLimit = Math.max(0, this.maxBytes - markerBytes - 1);
            this.value = new StringBuilder(Math.min(contentByteLimit, 16_384));
        }

        private PatchBuilder append(char character) {
            return append(String.valueOf(character));
        }

        private PatchBuilder append(String text) {
            if (truncated || text == null || text.isEmpty()) return this;
            int index = 0;
            while (index < text.length()) {
                int codePoint = text.codePointAt(index);
                int encodedLength = utf8Length(codePoint);
                if (byteCount + encodedLength > contentByteLimit) {
                    truncated = true;
                    break;
                }
                value.appendCodePoint(codePoint);
                byteCount += encodedLength;
                index += Character.charCount(codePoint);
            }
            return this;
        }

        private boolean truncated() {
            return truncated;
        }

        private void markTruncated() {
            truncated = true;
        }

        private String build() {
            if (truncated) {
                if (maxBytes < PATCH_TRUNCATED.getBytes(StandardCharsets.UTF_8).length + 1) return "";
                if (!value.isEmpty() && value.charAt(value.length() - 1) != '\n') value.append('\n');
                value.append(PATCH_TRUNCATED);
            }
            return value.toString();
        }
    }

    private static final class PatchBudget {
        private int remainingBytes = MAX_TOTAL_PATCH_BYTES;

        private String take(String patch) {
            if (patch == null || patch.isEmpty()) return patch;
            int patchBytes = patch.getBytes(StandardCharsets.UTF_8).length;
            if (patchBytes <= remainingBytes) {
                remainingBytes -= patchBytes;
                return patch;
            }
            int markerBytes = PATCH_TRUNCATED.getBytes(StandardCharsets.UTF_8).length;
            if (remainingBytes < markerBytes + 1) {
                remainingBytes = 0;
                return PATCH_OMITTED;
            }
            PatchBuilder bounded = new PatchBuilder(Math.min(MAX_PATCH_BYTES, remainingBytes));
            bounded.append(patch);
            String result = bounded.build();
            remainingBytes = Math.max(0, remainingBytes - result.getBytes(StandardCharsets.UTF_8).length);
            return result;
        }
    }
}
