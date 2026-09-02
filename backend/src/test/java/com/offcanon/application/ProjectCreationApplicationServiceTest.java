package com.offcanon.application;

import com.offcanon.infrastructure.git.GitSnapshotAdapter;
import com.offcanon.infrastructure.memory.InMemoryExperimentRepository;
import com.offcanon.infrastructure.memory.InMemoryProjectRepository;
import com.offcanon.infrastructure.memory.InMemoryPromotionLock;
import com.offcanon.infrastructure.process.ProcessRunner;
import com.offcanon.shared.domain.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectCreationApplicationServiceTest {
    @TempDir
    Path temp;

    private Path parent;
    private Path dataRoot;
    private InMemoryProjectRepository projects;
    private ProjectCreationApplicationService creator;
    private UUID owner;

    @BeforeEach
    void setUp() throws Exception {
        parent = Files.createDirectories(temp.resolve("projects"));
        dataRoot = temp.resolve("offcanon-data");
        projects = new InMemoryProjectRepository();
        var snapshots = new GitSnapshotAdapter(new ProcessRunner(), dataRoot.toString());
        var projectService = new ProjectApplicationService(projects, snapshots,
                new InMemoryExperimentRepository(), new InMemoryPromotionLock());
        creator = new ProjectCreationApplicationService(new ProcessRunner(), snapshots,
                projectService, dataRoot);
        owner = UUID.randomUUID();
    }

    @Test
    void createsMissingDirectoryInitializesGitAndRegistersProject() throws Exception {
        Path target = parent.resolve("new-app");

        var result = creator.create(owner, "New app", target.toString(), List.of("mvn test"));

        assertFalse(result.reopened());
        assertEquals(target.toRealPath(), result.project().canonicalPath());
        assertTrue(Files.isDirectory(target.resolve(".git")));
        assertEquals(0, runGit(target, "rev-parse", "--show-toplevel"));
        assertEquals(1, projects.findAll().size());
    }

    @Test
    void initializesAnExistingEmptyDirectoryButRejectsNonEmptyDirectory() throws Exception {
        Path empty = Files.createDirectories(parent.resolve("empty-app"));
        creator.create(owner, "Empty app", empty.toString(), List.of("npm test"));
        assertTrue(Files.isDirectory(empty.resolve(".git")));

        Path nonEmpty = Files.createDirectories(parent.resolve("non-empty-app"));
        Files.writeString(nonEmpty.resolve("README.md"), "keep me");

        DomainException error = assertThrows(DomainException.class,
                () -> creator.create(owner, "Non-empty", nonEmpty.toString(), List.of("mvn test")));

        assertEquals("PROJECT_TARGET_NOT_EMPTY", error.code());
        assertFalse(Files.exists(nonEmpty.resolve(".git")));
    }

    @Test
    void rejectsDataRootOverlapBeforeCreatingAnything() throws Exception {
        Path nested = dataRoot.resolve("inside");

        DomainException error = assertThrows(DomainException.class,
                () -> creator.create(owner, "Unsafe", nested.toString(), List.of("mvn test")));

        assertEquals("PROJECT_DATA_ROOT_OVERLAP", error.code());
        assertFalse(Files.exists(nested));
    }

    @Test
    void permitsCreatingAProjectWithoutVerificationCommands() throws Exception {
        Path target = parent.resolve("without-policy");

        var result = creator.create(owner, "Without policy", target.toString(), List.of());

        assertFalse(result.reopened());
        assertEquals(List.of(), result.project().verificationCommands());
        assertTrue(Files.isDirectory(target.resolve(".git")));
    }

    @Test
    void rejectsUnsafeDirectoryNamesBeforeTouchingTheParent() {
        Path target = parent.resolve("safe-target");

        DomainException dot = assertThrows(DomainException.class,
                () -> creator.create(owner, "Friendly name",
                        parent + java.io.File.separator + ".", List.of()));
        assertEquals("PROJECT_PATH_INVALID", dot.code());
        assertFalse(Files.exists(target));

        DomainException device = assertThrows(DomainException.class,
                () -> creator.create(owner, "Friendly name", parent.resolve("CON.txt").toString(), List.of()));
        assertEquals("PROJECT_PATH_INVALID", device.code());
        assertFalse(Files.exists(parent.resolve("CON.txt")));
    }

    @Test
    void removesGitInitializationButKeepsAnExistingEmptyDirectoryWhenRegistrationFails() throws Exception {
        Path target = Files.createDirectories(parent.resolve("existing-empty"));

        DomainException error = assertThrows(DomainException.class,
                () -> creator.create(null, "existing-empty", target.toString(), List.of()));

        assertEquals("OWNER_REQUIRED", error.code());
        assertTrue(Files.isDirectory(target));
        assertFalse(Files.exists(target.resolve(".git")));
        try (var children = Files.list(target)) {
            assertTrue(children.findAny().isEmpty());
        }
    }

    @Test
    void rollsBackADirectoryCreatedByThisOperationWhenRegistrationFails() {
        Path target = parent.resolve("rollback");

        DomainException error = assertThrows(DomainException.class,
                () -> creator.create(null, "Rollback", target.toString(), List.of("mvn test")));

        assertEquals("OWNER_REQUIRED", error.code());
        assertFalse(Files.exists(target));
    }

    private int runGit(Path cwd, String... args) {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.addAll(List.of(args));
        return new ProcessRunner().run(command, cwd, Map.of(), Duration.ofSeconds(20)).exitCode();
    }
}
