package com.offcanon.infrastructure.agent;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.shared.domain.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileDiscoveryToolTest {
    @TempDir
    Path workspace;

    @Test
    void searchStopsAtTheMatchLimitAndSkipsRuntimeDirectories() throws Exception {
        for (int index = 0; index < 250; index++) {
            Files.writeString(workspace.resolve("match-%03d.txt".formatted(index)), "needle\n");
        }
        Files.createDirectories(workspace.resolve("node_modules/package"));
        Files.writeString(workspace.resolve("node_modules/package/hidden.txt"), "needle-hidden\n");

        var result = new SearchFilesTool(new WorkspacePathResolver()).execute(
                experiment(), "search", Map.of("path", ".", "query", "needle"));

        assertTrue(result.success(), result.error());
        var lines = result.output().lines().toList();
        assertEquals(201, lines.size());
        assertEquals("...[match limit reached]", lines.get(200));
        assertFalse(result.output().contains("node_modules"));
    }

    @Test
    void listUsesABoundedSortedTopKAndReportsTruncation() throws Exception {
        for (int index = 599; index >= 0; index--) {
            Files.writeString(workspace.resolve("file-%03d.txt".formatted(index)), "x");
        }
        Files.createDirectories(workspace.resolve("target/generated"));
        Files.writeString(workspace.resolve("target/generated/hidden.txt"), "x");

        var result = new ListFilesTool(new WorkspacePathResolver()).execute(
                experiment(), "list", Map.of("path", "."));

        assertTrue(result.success(), result.error());
        var lines = result.output().lines().toList();
        assertEquals(501, lines.size());
        assertEquals("file-000.txt", lines.get(0));
        assertEquals("file-499.txt", lines.get(499));
        assertEquals("...[file limit reached]", lines.get(500));
        assertFalse(result.output().contains("target/"));
        assertTrue(isSorted(lines.subList(0, 500).toArray(String[]::new)));
    }

    @Test
    void traversalHonorsThreadInterruption() {
        Thread.currentThread().interrupt();
        try {
            DomainException search = assertThrows(DomainException.class, () ->
                    new SearchFilesTool(new WorkspacePathResolver()).execute(
                            experiment(), "search", Map.of("path", ".", "query", "needle")));
            assertEquals("TOOL_INTERRUPTED", search.code());
        } finally {
            Thread.interrupted();
        }

        Thread.currentThread().interrupt();
        try {
            DomainException list = assertThrows(DomainException.class, () ->
                    new ListFilesTool(new WorkspacePathResolver()).execute(
                            experiment(), "list", Map.of("path", ".")));
            assertEquals("TOOL_INTERRUPTED", list.code());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void disclosesWhenLargeFilesWereSkippedDuringSearch() throws Exception {
        Path large = workspace.resolve("large.txt");
        Files.write(large, new byte[512_001]);

        var result = new SearchFilesTool(new WorkspacePathResolver()).execute(
                experiment(), "large-search", Map.of("path", ".", "query", "needle"));

        assertTrue(result.success(), result.error());
        assertTrue(result.output().contains("files larger than 512000 bytes skipped"));
    }

    @Test
    void omitsSensitiveEnvironmentFilesFromListingAndSearch() throws Exception {
        Files.writeString(workspace.resolve(".env"), "TOKEN=must-not-enter-context\nneedle\n");
        Files.writeString(workspace.resolve(".env.example"), "TOKEN=replace-me\nneedle-template\n");
        Files.createDirectories(workspace.resolve("config/.env.local"));
        Files.writeString(workspace.resolve("config/.env.local/secret.txt"), "needle\n");

        var search = new SearchFilesTool(new WorkspacePathResolver()).execute(
                experiment(), "search-sensitive", Map.of("path", ".", "query", "needle"));
        var list = new ListFilesTool(new WorkspacePathResolver()).execute(
                experiment(), "list-sensitive", Map.of("path", "."));

        assertTrue(search.success(), search.error());
        assertTrue(list.success(), list.error());
        assertFalse(search.output().contains("TOKEN"));
        assertTrue(search.output().contains(".env.example"));
        assertTrue(list.output().contains(".env.example"));
        assertFalse(search.output().contains("config/.env.local"));
        assertFalse(list.output().contains("config/.env.local"));
        assertTrue(search.output().contains("sensitive files omitted"));
        assertTrue(list.output().contains("sensitive files omitted"));
    }

    @Test
    void discoversConventionalDeepSourceTreesAndReportsDeeperTruncation() throws Exception {
        Path source = workspace.resolve("src/main/java/com/example/deep");
        Files.createDirectories(source);
        Files.writeString(source.resolve("App.java"), "needle\n");

        var search = new SearchFilesTool(new WorkspacePathResolver()).execute(
                experiment(), "search-deep", Map.of("path", ".", "query", "needle"));
        var list = new ListFilesTool(new WorkspacePathResolver()).execute(
                experiment(), "list-deep", Map.of("path", "."));

        assertTrue(search.success(), search.error());
        assertTrue(search.output().contains("src/main/java/com/example/deep/App.java:1: needle"));
        assertTrue(list.success(), list.error());
        assertTrue(list.output().contains("src/main/java/com/example/deep/App.java"));

        Path veryDeep = workspace;
        for (int index = 0; index < 17; index++) {
            veryDeep = veryDeep.resolve("level" + index);
        }
        Files.createDirectories(veryDeep);
        Files.writeString(veryDeep.resolve("hidden.txt"), "needle-hidden\n");

        var boundedSearch = new SearchFilesTool(new WorkspacePathResolver()).execute(
                experiment(), "search-bounded", Map.of("path", ".", "query", "needle-hidden"));
        var boundedList = new ListFilesTool(new WorkspacePathResolver()).execute(
                experiment(), "list-bounded", Map.of("path", "."));

        assertTrue(boundedSearch.success(), boundedSearch.error());
        assertTrue(boundedSearch.output().contains("directory depth limit reached"));
        assertTrue(boundedList.success(), boundedList.error());
        assertTrue(boundedList.output().contains("directory depth limit reached"));
    }

    private Experiment experiment() {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), workspace);
        return experiment;
    }

    private boolean isSorted(String[] values) {
        String[] sorted = values.clone();
        Arrays.sort(sorted);
        return Arrays.equals(values, sorted);
    }
}
