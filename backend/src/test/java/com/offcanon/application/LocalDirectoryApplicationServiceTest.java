package com.offcanon.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offcanon.infrastructure.process.ProcessRunner;
import com.offcanon.shared.domain.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalDirectoryApplicationServiceTest {
    @TempDir
    Path temp;

    @Test
    void listsChildDirectoriesAndDetectsTheContainingGitRoot() throws Exception {
        Path repository = Files.createDirectories(temp.resolve("repository"));
        run(repository, "git", "init", "-q");
        Files.createDirectories(repository.resolve("src/main"));
        Files.createDirectories(repository.resolve("node_modules/ignored"));
        Files.writeString(repository.resolve("README.md"), "demo");

        LocalDirectoryApplicationService service = new LocalDirectoryApplicationService(
                new ProcessRunner(), new ObjectMapper(), temp, temp);
        var listing = service.browse(repository.resolve("src").toString());

        assertEquals(repository.toRealPath(), listing.gitRoot());
        assertEquals("main", listing.entries().getFirst().name());
        assertTrue(listing.entries().stream().noneMatch(entry -> entry.name().equals("node_modules")));
        assertTrue(listing.locations().stream().anyMatch(location -> location.kind().equals("HOME")));
    }

    @Test
    void returnsDirectoriesOnlyAndSkipsOffcanonInternals() throws Exception {
        Path directory = Files.createDirectories(temp.resolve("directory"));
        Files.createDirectories(directory.resolve("visible"));
        Files.createDirectories(directory.resolve(".offcanon"));
        Files.writeString(directory.resolve("file.txt"), "not a directory");

        var listing = new LocalDirectoryApplicationService(new ProcessRunner(), new ObjectMapper(), temp, temp)
                .browse(directory.toString());

        assertEquals(List.of("visible"), listing.entries().stream().map(entry -> entry.name()).toList());
        assertFalse(listing.entries().stream().anyMatch(entry -> entry.name().equals("file.txt")));
    }

    @Test
    void suggestsProjectMetadataFromCommonBuildFiles() throws Exception {
        Path repository = Files.createDirectories(temp.resolve("sample-service"));
        run(repository, "git", "init", "-q");
        Files.writeString(repository.resolve("pom.xml"), "<project />");
        Files.writeString(repository.resolve("package.json"), """
                {"scripts":{"test":"vitest run"}}
                """);
        Files.writeString(repository.resolve("package-lock.json"), "{}");

        var listing = new LocalDirectoryApplicationService(
                new ProcessRunner(), new ObjectMapper(), temp, temp).browse(repository.toString());

        assertEquals("sample-service", listing.suggestedName());
        assertEquals(List.of("mvn test", "npm test"), listing.suggestedVerificationCommands());
    }

    @Test
    void rejectsRelativeAndMissingDirectories() {
        LocalDirectoryApplicationService service = new LocalDirectoryApplicationService(
                new ProcessRunner(), new ObjectMapper(), temp, temp);

        DomainException relative = assertThrows(DomainException.class, () -> service.browse("relative/path"));
        assertEquals("DIRECTORY_PATH_ABSOLUTE_REQUIRED", relative.code());

        DomainException missing = assertThrows(DomainException.class,
                () -> service.browse(temp.resolve("missing").toString()));
        assertEquals("DIRECTORY_NOT_FOUND", missing.code());
    }

    private void run(Path cwd, String... command) {
        ProcessRunner.ProcessResult result = new ProcessRunner().run(List.of(command), cwd, Map.of(), Duration.ofSeconds(20));
        assertEquals(0, result.exitCode(), result.stderr());
    }
}
