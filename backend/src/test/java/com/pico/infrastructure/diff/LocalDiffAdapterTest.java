package com.pico.infrastructure.diff;

import com.pico.workspace.domain.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        Files.writeString(basePath.resolve("service.txt"), "one\ntwo\n");
        Files.writeString(resultPath.resolve("service.txt"), "one\nchanged\nthree\n");
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
}
