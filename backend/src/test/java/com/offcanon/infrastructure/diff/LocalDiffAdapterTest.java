package com.offcanon.infrastructure.diff;

import com.offcanon.workspace.domain.Snapshot;
import com.offcanon.port.DiffPort;
import com.offcanon.shared.domain.DomainException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalDiffAdapterTest {
    private static final int MAX_PATCH_BYTES = 256 * 1024;

    @TempDir
    Path temp;

    @Test
    void producesReviewableTextPatchAndLineCounts() throws Exception {
        Path basePath = temp.resolve("base");
        Path resultPath = temp.resolve("result");
        Files.createDirectories(basePath);
        Files.createDirectories(resultPath);
        Files.writeString(basePath.resolve("service.txt"), "one\r\ntwo\n");
        Files.writeString(resultPath.resolve("service.txt"), "one\u2028changed\u0085three\n");
        Snapshot base = new Snapshot(UUID.randomUUID(), UUID.randomUUID(), "fingerprint", basePath,
                Instant.now(), List.of("service.txt"), List.of());

        var diff = new LocalDiffAdapter().compare(base, resultPath);

        assertEquals(1, diff.size());
        assertEquals(2, diff.get(0).additions());
        assertEquals(1, diff.get(0).deletions());
        assertTrue(diff.get(0).patch().contains("-two"));
        assertTrue(diff.get(0).patch().contains("+changed"));
        assertTrue(diff.get(0).patch().contains("+three"));
    }

    @Test
    void reportsTrackedModeOnlyChanges() throws Exception {
        Path basePath = temp.resolve("mode-base");
        Path resultPath = temp.resolve("mode-result");
        Files.createDirectories(basePath);
        Files.createDirectories(resultPath);
        Path baseFile = basePath.resolve("run.sh");
        Path resultFile = resultPath.resolve("run.sh");
        Files.writeString(baseFile, "echo run\n");
        Files.writeString(resultFile, "echo run\n");
        try {
            Files.setPosixFilePermissions(baseFile, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            Files.setPosixFilePermissions(resultFile, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException error) {
            Assumptions.assumeTrue(false, "POSIX permissions are unavailable on this workstation");
        }
        Snapshot base = new Snapshot(UUID.randomUUID(), UUID.randomUUID(), "fingerprint", basePath,
                Instant.now(), List.of("run.sh"), List.of());

        var diff = new LocalDiffAdapter().compare(base, resultPath);

        assertEquals(1, diff.size());
        assertEquals(DiffPort.DiffEntry.Change.MODIFIED, diff.getFirst().change());
        assertEquals(0, diff.getFirst().additions());
        assertEquals(0, diff.getFirst().deletions());
        assertTrue(diff.getFirst().patch().contains("old mode 100644"));
        assertTrue(diff.getFirst().patch().contains("new mode 100755"));
    }

    @Test
    void ignoresBuildArtifactsAndDoesNotLoadUnchangedLargeFilesForPreview() throws Exception {
        Path basePath = temp.resolve("large-base");
        Path resultPath = temp.resolve("large-result");
        Files.createDirectories(basePath.resolve("target"));
        Files.createDirectories(resultPath.resolve("target"));
        byte[] large = new byte[2_000_001];
        Files.write(basePath.resolve("asset.bin"), large);
        Files.write(resultPath.resolve("asset.bin"), large);
        Files.writeString(resultPath.resolve("target/generated.txt"), "build output");
        Snapshot base = new Snapshot(UUID.randomUUID(), UUID.randomUUID(), "fingerprint", basePath,
                Instant.now(), List.of("asset.bin"), List.of());

        assertTrue(new LocalDiffAdapter().compare(base, resultPath).isEmpty());
    }

    @Test
    void reportsChangedLargeFileWithoutFailingTheWholeDiff() throws Exception {
        Path basePath = temp.resolve("changed-large-base");
        Path resultPath = temp.resolve("changed-large-result");
        Files.createDirectories(basePath);
        Files.createDirectories(resultPath);
        byte[] before = new byte[2_000_001];
        byte[] after = before.clone();
        after[after.length - 1] = 1;
        Files.write(basePath.resolve("asset.bin"), before);
        Files.write(resultPath.resolve("asset.bin"), after);
        Snapshot base = new Snapshot(UUID.randomUUID(), UUID.randomUUID(), "fingerprint", basePath,
                Instant.now(), List.of("asset.bin"), List.of());

        var diff = new LocalDiffAdapter().compare(base, resultPath);

        assertEquals(1, diff.size());
        assertTrue(diff.getFirst().binary());
        assertFalse(diff.getFirst().patch().isBlank());
    }

    @Test
    void boundsPreviewForHundredsOfThousandsOfShortLines() throws Exception {
        Path basePath = temp.resolve("many-lines-base");
        Path resultPath = temp.resolve("many-lines-result");
        Files.createDirectories(basePath);
        Files.createDirectories(resultPath);
        int lineCount = 500_000;
        Files.writeString(basePath.resolve("generated.txt"), "a\n".repeat(lineCount));
        Files.writeString(resultPath.resolve("generated.txt"), "b\n".repeat(lineCount));
        Snapshot base = new Snapshot(UUID.randomUUID(), UUID.randomUUID(), "fingerprint", basePath,
                Instant.now(), List.of("generated.txt"), List.of());

        var diff = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> new LocalDiffAdapter().compare(base, resultPath));

        assertEquals(1, diff.size());
        assertEquals(lineCount, diff.getFirst().additions());
        assertEquals(lineCount, diff.getFirst().deletions());
        assertFalse(diff.getFirst().binary());
        assertTrue(diff.getFirst().patch().contains("... [diff truncated]"));
        assertTrue(diff.getFirst().patch().lines().count() <= 2_004);
        assertTrue(diff.getFirst().patch().length() < 20_000);
    }

    @Test
    void boundsAddedFilePreviewForOneVeryLongUnicodeLine() throws Exception {
        Path basePath = temp.resolve("long-added-base");
        Path resultPath = temp.resolve("long-added-result");
        Files.createDirectories(basePath);
        Files.createDirectories(resultPath);
        Files.writeString(resultPath.resolve("emoji-added.txt"), "\ud83d\ude00".repeat(350_000));
        Snapshot base = new Snapshot(UUID.randomUUID(), UUID.randomUUID(), "fingerprint", basePath,
                Instant.now(), List.of(), List.of());

        var diff = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> new LocalDiffAdapter().compare(base, resultPath));

        assertEquals(1, diff.size());
        assertEquals(DiffPort.DiffEntry.Change.ADDED, diff.getFirst().change());
        assertEquals(1, diff.getFirst().additions());
        assertEquals(0, diff.getFirst().deletions());
        assertBoundedPatch(diff.getFirst().patch());
    }

    @Test
    void boundsModifiedFilePreviewByUtf8Bytes() throws Exception {
        Path basePath = temp.resolve("long-modified-base");
        Path resultPath = temp.resolve("long-modified-result");
        Files.createDirectories(basePath);
        Files.createDirectories(resultPath);
        Files.writeString(basePath.resolve("cjk-modified.txt"), "\u65e7".repeat(500_000));
        Files.writeString(resultPath.resolve("cjk-modified.txt"), "\u65b0".repeat(500_000));
        Snapshot base = new Snapshot(UUID.randomUUID(), UUID.randomUUID(), "fingerprint", basePath,
                Instant.now(), List.of("cjk-modified.txt"), List.of());

        var diff = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> new LocalDiffAdapter().compare(base, resultPath));

        assertEquals(1, diff.size());
        assertEquals(DiffPort.DiffEntry.Change.MODIFIED, diff.getFirst().change());
        assertEquals(1, diff.getFirst().additions());
        assertEquals(1, diff.getFirst().deletions());
        assertBoundedPatch(diff.getFirst().patch());
    }

    @Test
    void boundsModifiedPreviewWithoutSplittingSurrogatePairs() throws Exception {
        Path basePath = temp.resolve("emoji-modified-base");
        Path resultPath = temp.resolve("emoji-modified-result");
        Files.createDirectories(basePath);
        Files.createDirectories(resultPath);
        Files.writeString(basePath.resolve("emoji-modified.txt"), "\ud83d\ude00".repeat(350_000));
        Files.writeString(resultPath.resolve("emoji-modified.txt"), "\ud83d\ude01".repeat(350_000));
        Snapshot base = new Snapshot(UUID.randomUUID(), UUID.randomUUID(), "fingerprint", basePath,
                Instant.now(), List.of("emoji-modified.txt"), List.of());

        var diff = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> new LocalDiffAdapter().compare(base, resultPath));

        assertEquals(1, diff.size());
        assertEquals(DiffPort.DiffEntry.Change.MODIFIED, diff.getFirst().change());
        assertEquals(1, diff.getFirst().additions());
        assertEquals(1, diff.getFirst().deletions());
        assertBoundedPatch(diff.getFirst().patch());
    }

    @Test
    void boundsDeletedFilePreviewForOneVeryLongUnicodeLine() throws Exception {
        Path basePath = temp.resolve("long-deleted-base");
        Path resultPath = temp.resolve("long-deleted-result");
        Files.createDirectories(basePath);
        Files.createDirectories(resultPath);
        Files.writeString(basePath.resolve("emoji-deleted.txt"), "\ud83d\ude00".repeat(350_000));
        Snapshot base = new Snapshot(UUID.randomUUID(), UUID.randomUUID(), "fingerprint", basePath,
                Instant.now(), List.of("emoji-deleted.txt"), List.of());

        var diff = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> new LocalDiffAdapter().compare(base, resultPath));

        assertEquals(1, diff.size());
        assertEquals(DiffPort.DiffEntry.Change.DELETED, diff.getFirst().change());
        assertEquals(0, diff.getFirst().additions());
        assertEquals(1, diff.getFirst().deletions());
        assertBoundedPatch(diff.getFirst().patch());
    }

    @Test
    void boundsAggregatePatchPreviewAcrossManyLargeChanges() throws Exception {
        Path basePath = temp.resolve("aggregate-base");
        Path resultPath = temp.resolve("aggregate-result");
        Files.createDirectories(basePath);
        Files.createDirectories(resultPath);
        for (int index = 0; index < 18; index++) {
            String name = "generated-" + index + ".txt";
            Files.writeString(basePath.resolve(name), "a".repeat(300_000));
            Files.writeString(resultPath.resolve(name), "b".repeat(300_000));
        }
        Snapshot base = new Snapshot(UUID.randomUUID(), UUID.randomUUID(), "fingerprint", basePath,
                Instant.now(), List.of(), List.of());

        var diff = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> new LocalDiffAdapter().compare(base, resultPath));

        assertEquals(18, diff.size());
        int patchBytes = diff.stream()
                .mapToInt(item -> item.patch().getBytes(StandardCharsets.UTF_8).length)
                .sum();
        int omissionOverhead = diff.size()
                * "... [diff preview omitted: response limit reached]\n".getBytes(StandardCharsets.UTF_8).length;
        assertTrue(patchBytes <= 4 * 1024 * 1024 + omissionOverhead);
        assertTrue(diff.stream().anyMatch(item -> item.patch().contains("response limit reached")));
        assertTrue(diff.stream().allMatch(item -> item.additions() == 1 && item.deletions() == 1));
    }

    @Test
    void rejectsMoreChangedFilesThanCanBeReturnedCompletely() throws Exception {
        Path basePath = temp.resolve("entry-limit-base");
        Path resultPath = temp.resolve("entry-limit-result");
        Files.createDirectories(basePath);
        Files.createDirectories(resultPath);
        for (int index = 0; index <= 1_000; index++) {
            Files.writeString(resultPath.resolve("changed-" + index + ".txt"), "changed\n");
        }
        Snapshot base = new Snapshot(UUID.randomUUID(), UUID.randomUUID(), "fingerprint", basePath,
                Instant.now(), List.of(), List.of());

        DomainException error = assertThrows(DomainException.class,
                () -> new LocalDiffAdapter().compare(base, resultPath));

        assertEquals("DIFF_TOO_LARGE", error.code());
    }

    @Test
    void stopsDiscoveryWhenTheFilesystemWalkExceedsItsBudget() throws Exception {
        Path basePath = temp.resolve("walk-limit-base");
        Path resultPath = temp.resolve("walk-limit-result");
        Files.createDirectories(basePath);
        Files.createDirectories(resultPath);
        Files.writeString(resultPath.resolve("one.txt"), "one\n");
        Files.writeString(resultPath.resolve("two.txt"), "two\n");
        Snapshot base = new Snapshot(UUID.randomUUID(), UUID.randomUUID(), "fingerprint", basePath,
                Instant.now(), List.of(), List.of());

        DomainException error = assertThrows(DomainException.class,
                () -> new LocalDiffAdapter(2).compare(base, resultPath));

        assertEquals("DIFF_TOO_LARGE", error.code());
    }

    @Test
    void reportsWorkspaceSymlinksInsteadOfSilentlyHidingThem() throws Exception {
        Path basePath = temp.resolve("symlink-base");
        Path resultPath = temp.resolve("symlink-result");
        Files.createDirectories(basePath);
        Files.createDirectories(resultPath);
        Files.writeString(resultPath.resolve("target.txt"), "target\n");
        try {
            Files.createSymbolicLink(resultPath.resolve("link.txt"), Path.of("target.txt"));
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException error) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable on this workstation");
        }
        Snapshot base = new Snapshot(UUID.randomUUID(), UUID.randomUUID(), "fingerprint", basePath,
                Instant.now(), List.of(), List.of());

        DomainException error = assertThrows(DomainException.class,
                () -> new LocalDiffAdapter().compare(base, resultPath));

        assertEquals("DIFF_SYMLINK_BLOCKED", error.code());
    }

    private void assertBoundedPatch(String patch) {
        assertTrue(patch.contains("... [diff truncated]"));
        assertTrue(patch.getBytes(StandardCharsets.UTF_8).length <= MAX_PATCH_BYTES);
        for (int index = 0; index < patch.length(); index++) {
            char value = patch.charAt(index);
            if (Character.isHighSurrogate(value)) {
                assertTrue(index + 1 < patch.length() && Character.isLowSurrogate(patch.charAt(index + 1)));
                index++;
            } else {
                assertFalse(Character.isLowSurrogate(value));
            }
        }
    }
}
