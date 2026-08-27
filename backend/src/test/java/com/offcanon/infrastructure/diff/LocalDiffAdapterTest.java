package com.offcanon.infrastructure.diff;

import com.offcanon.workspace.domain.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalDiffAdapterTest {
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
}
