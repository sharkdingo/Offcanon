package com.offcanon.application;

import com.offcanon.infrastructure.git.GitSnapshotAdapter;
import com.offcanon.infrastructure.memory.InMemoryProjectRepository;
import com.offcanon.infrastructure.process.ProcessRunner;
import com.offcanon.project.domain.Project;
import com.offcanon.shared.domain.DomainException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectApplicationServiceTest {
    @TempDir
    Path temp;

    private Path repository;
    private InMemoryProjectRepository projects;
    private ProjectApplicationService service;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws Exception {
        repository = Files.createDirectories(temp.resolve("repository"));
        run(repository, "git", "init", "-q");
        projects = new InMemoryProjectRepository();
        service = new ProjectApplicationService(projects,
                new GitSnapshotAdapter(new ProcessRunner(), temp.resolve("offcanon-data").toString()));
    }

    @AfterEach
    void tearDown() {
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void rejectsAnExactlyRepeatedCanonicalPath() {
        Project registered = register("first", repository);

        DomainException error = assertThrows(DomainException.class,
                () -> register("duplicate", repository));

        assertEquals("PROJECT_ALREADY_REGISTERED", error.code());
        assertTrue(error.getMessage().contains(registered.id().toString()));
        assertEquals(1, projects.findAll().size());
    }

    @Test
    void rejectsAGitSubdirectoryAsCanonicalScope() throws Exception {
        Path nested = Files.createDirectories(repository.resolve("src/main"));

        DomainException error = assertThrows(DomainException.class,
                () -> register("nested", nested));

        assertEquals("PROJECT_SCOPE_MISMATCH", error.code());
        assertEquals(0, projects.findAll().size());
    }

    @Test
    void resolvesAWindowsCaseAliasToTheSameCanonicalRoot() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"),
                "Case-insensitive path aliases are specific to Windows");
        Path alias = Path.of(repository.toString().toUpperCase(Locale.ROOT));

        Project registered = register("case-alias", alias);

        assertEquals(repository.toRealPath(), registered.canonicalPath());
        DomainException error = assertThrows(DomainException.class,
                () -> register("root", repository));
        assertEquals("PROJECT_ALREADY_REGISTERED", error.code());
        assertEquals(1, projects.findAll().size());
    }

    @Test
    void rejectsConcurrentRegistrationOfTheSameCanonicalRoot() throws Exception {
        int attempts = 8;
        executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int index = 0; index < attempts; index++) {
            int attempt = index;
            results.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    register("project-" + attempt, repository);
                    return true;
                } catch (DomainException error) {
                    assertEquals("PROJECT_ALREADY_REGISTERED", error.code());
                    return false;
                }
            }));
        }
        assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
        start.countDown();

        int successes = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) successes++;
        }
        assertEquals(1, successes);
        assertEquals(1, projects.findAll().size());
    }

    @Test
    void resolvesASymlinkAliasWhenSupported() throws Exception {
        Path alias = temp.resolve("repository-link");
        try {
            Files.createSymbolicLink(alias, repository);
        } catch (IOException | UnsupportedOperationException | SecurityException error) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "Directory symlinks are unavailable on this workstation");
        }

        Project registered = register("alias", alias);

        assertEquals(repository.toRealPath(), registered.canonicalPath());
        DomainException error = assertThrows(DomainException.class,
                () -> register("root", repository));
        assertEquals("PROJECT_ALREADY_REGISTERED", error.code());
    }

    private Project register(String name, Path path) {
        return service.register(name, path.toString(), List.of("mvn test"));
    }

    private void run(Path cwd, String... command) {
        ProcessRunner.ProcessResult result = new ProcessRunner().run(List.of(command), cwd, Map.of(), Duration.ofSeconds(20));
        if (result.exitCode() != 0) throw new AssertionError(result.stderr());
    }
}
