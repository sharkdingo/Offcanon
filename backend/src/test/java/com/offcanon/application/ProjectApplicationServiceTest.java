package com.offcanon.application;

import com.offcanon.infrastructure.git.GitSnapshotAdapter;
import com.offcanon.infrastructure.memory.InMemoryProjectRepository;
import com.offcanon.infrastructure.memory.InMemoryExperimentRepository;
import com.offcanon.infrastructure.memory.InMemoryPromotionLock;
import com.offcanon.infrastructure.memory.InMemoryPromotionJournal;
import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.promotion.domain.PromotionJournal;
import com.offcanon.infrastructure.process.ProcessRunner;
import com.offcanon.project.domain.Project;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.verification.domain.VerificationResult;
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
import java.util.UUID;
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
    private UUID fixtureOwner;

    @BeforeEach
    void setUp() throws Exception {
        repository = Files.createDirectories(temp.resolve("repository"));
        run(repository, "git", "init", "-q");
        projects = new InMemoryProjectRepository();
        fixtureOwner = UUID.randomUUID();
        service = new ProjectApplicationService(projects,
                new GitSnapshotAdapter(new ProcessRunner(), temp.resolve("offcanon-data").toString()),
                new InMemoryExperimentRepository(), new InMemoryPromotionLock());
    }

    @AfterEach
    void tearDown() {
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void reopensAnExactlyRepeatedCanonicalPathForTheSameOwner() {
        Project registered = register("first", repository);

        Project reopened = service.register(registered.ownerId(), "renamed", repository.toString(), List.of("gradle test"));

        assertEquals(registered.id(), reopened.id());
        assertEquals("first", reopened.name());
        assertEquals(List.of("mvn test"), reopened.verificationCommands());
        assertEquals(1, projects.findAll().size());
    }

    @Test
    void reportsWhetherRegistrationReopenedAnExistingProject() {
        Project registered = register("first", repository);

        ProjectApplicationService.RegistrationResult created = service.registerWithOutcome(
                registered.ownerId(), "duplicate", repository.toString(), List.of());

        assertTrue(created.reopened());
        assertEquals(registered.id(), created.project().id());
    }

    @Test
    void permitsRegisteringAndUpdatingAProjectWithoutVerificationCommands() {
        Project registered = service.register(fixtureOwner, "without-policy", repository.toString(), List.of());

        assertEquals(List.of(), registered.verificationCommands());
        Project updated = service.update(fixtureOwner, registered.id(), "without-policy-renamed",
                repository.toString(), List.of());

        assertEquals(List.of(), updated.verificationCommands());
        assertEquals("without-policy-renamed", updated.name());
    }

    @Test
    void updatesMetadataWithoutChangingCanonicalIdentity() {
        Project registered = register("first", repository);

        Project updated = service.update(fixtureOwner, registered.id(), "renamed",
                repository.toString(), List.of("gradle test", "npm test"));

        assertEquals(registered.id(), updated.id());
        assertEquals("renamed", updated.name());
        assertEquals(List.of("gradle test", "npm test"), updated.verificationCommands());
        assertEquals(registered.canonicalPath(), updated.canonicalPath());
        assertEquals(1, updated.version());
        assertEquals(updated, projects.findById(registered.id()).orElseThrow());
    }

    @Test
    void rejectsChangingCanonicalIdentityDuringMetadataUpdate() {
        Project registered = register("first", repository);
        Path another = temp.resolve("another");
        try {
            Files.createDirectories(another);
            run(another, "git", "init", "-q");
        } catch (Exception error) {
            throw new AssertionError(error);
        }

        DomainException error = assertThrows(DomainException.class, () -> service.update(
                fixtureOwner, registered.id(), "renamed", another.toString(), List.of("mvn test")));

        assertEquals("PROJECT_PATH_IMMUTABLE", error.code());
        assertEquals("first", projects.findById(registered.id()).orElseThrow().name());
    }

    @Test
    void locksAcceptancePolicyWhileAnExperimentIsActive() {
        Project registered = register("first", repository);
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        experiments.save(Experiment.create(registered.id(), UUID.randomUUID(), "active task", java.time.Instant.now()));
        ProjectApplicationService guarded = new ProjectApplicationService(projects,
                new GitSnapshotAdapter(new ProcessRunner(), temp.resolve("offcanon-data-guarded").toString()),
                experiments, new InMemoryPromotionLock());

        DomainException error = assertThrows(DomainException.class, () -> guarded.update(
                fixtureOwner, registered.id(), "renamed", repository.toString(), List.of("gradle test")));

        assertEquals("VERIFICATION_POLICY_LOCKED", error.code());
        assertEquals(List.of("mvn test"), projects.findById(registered.id()).orElseThrow().verificationCommands());
    }

    @Test
    void allowsAddingAFirstVerificationPolicyToASealedResultWaitingForVerification() {
        Project registered = service.register(fixtureOwner, "without-policy", repository.toString(), List.of());
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        Experiment waiting = Experiment.create(registered.id(), UUID.randomUUID(), "waiting", java.time.Instant.now());
        experiments.save(waiting);
        waiting.beginSnapshot();
        experiments.save(waiting);
        waiting.attachBase(UUID.randomUUID(), repository);
        experiments.save(waiting);
        waiting.start();
        experiments.save(waiting);
        waiting.markAgentCompleted("done");
        experiments.save(waiting);
        waiting.sealResult(UUID.randomUUID());
        experiments.save(waiting);
        ProjectApplicationService guarded = new ProjectApplicationService(projects,
                new GitSnapshotAdapter(new ProcessRunner(), temp.resolve("offcanon-data-waiting").toString()),
                experiments, new InMemoryPromotionLock());

        Project updated = guarded.update(fixtureOwner, registered.id(), "configured",
                repository.toString(), List.of("mvn test"));

        assertEquals(List.of("mvn test"), updated.verificationCommands());

        Project changed = guarded.update(fixtureOwner, registered.id(), "configured",
                repository.toString(), List.of("gradle test"));
        assertEquals(List.of("gradle test"), changed.verificationCommands());

        Project cleared = guarded.update(fixtureOwner, registered.id(), "configured",
                repository.toString(), List.of());
        assertEquals(List.of(), cleared.verificationCommands());
    }

    @Test
    void changingPolicyInvalidatesVerifiedResultsAndKeepsThemReverifiable() {
        Project registered = register("first", repository);
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        Experiment verified = Experiment.create(registered.id(), UUID.randomUUID(), "verified", java.time.Instant.now());
        experiments.save(verified);
        verified.beginSnapshot();
        experiments.save(verified);
        verified.attachBase(UUID.randomUUID(), repository);
        experiments.save(verified);
        verified.start();
        experiments.save(verified);
        verified.markAgentCompleted("done");
        experiments.save(verified);
        UUID resultSnapshotId = UUID.randomUUID();
        verified.sealResult(resultSnapshotId);
        experiments.save(verified);
        verified.beginVerification();
        experiments.save(verified);
        verified.markVerified(VerificationResult.passed(List.of()));
        experiments.save(verified);

        ProjectApplicationService guarded = new ProjectApplicationService(projects,
                new GitSnapshotAdapter(new ProcessRunner(), temp.resolve("offcanon-data-verified").toString()),
                experiments, new InMemoryPromotionLock());

        Project updated = guarded.update(fixtureOwner, registered.id(), "first",
                repository.toString(), List.of("gradle test"));

        assertEquals(List.of("gradle test"), updated.verificationCommands());
        Experiment invalidated = experiments.findById(verified.id()).orElseThrow();
        assertEquals(ExperimentStatus.AGENT_COMPLETED, invalidated.status());
        assertEquals(resultSnapshotId, invalidated.resultSnapshotId());
        assertTrue(invalidated.failureReason().startsWith("VERIFICATION_POLICY_CHANGED:"));
        assertTrue(invalidated.verificationResult() == null);
    }

    @Test
    void blocksPolicyChangeWhilePromotionJournalIsUnresolved() {
        Project registered = register("first", repository);
        InMemoryPromotionJournal journals = new InMemoryPromotionJournal();
        journals.create(PromotionJournal.create(UUID.randomUUID(), registered.id(),
                "base-fingerprint", "candidate-fingerprint", repository.resolve(".candidate"),
                "test-owner", java.time.Instant.now(), java.time.Instant.now().plusSeconds(600)));
        ProjectApplicationService guarded = new ProjectApplicationService(projects,
                new GitSnapshotAdapter(new ProcessRunner(), temp.resolve("offcanon-data-journal").toString()),
                new InMemoryExperimentRepository(), new InMemoryPromotionLock(), journals);

        DomainException error = assertThrows(DomainException.class, () -> guarded.update(
                fixtureOwner, registered.id(), "renamed", repository.toString(), List.of("gradle test")));

        assertEquals("VERIFICATION_POLICY_LOCKED", error.code());
        assertEquals(List.of("mvn test"), projects.findById(registered.id()).orElseThrow().verificationCommands());
    }

    @Test
    void rejectsACanonicalPathRegisteredByAnotherOwner() {
        Project registered = service.register(UUID.randomUUID(), "first", repository.toString(), List.of("mvn test"));

        DomainException error = assertThrows(DomainException.class,
                () -> service.register(UUID.randomUUID(), "duplicate", repository.toString(), List.of("mvn test")));

        assertEquals("PROJECT_ALREADY_REGISTERED", error.code());
        assertTrue(error.getMessage().contains("another account"));
        assertTrue(!error.getMessage().contains(registered.id().toString()));
        assertTrue(!error.getMessage().contains(repository.toString()));
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
        Project reopened = register("root", repository);
        assertEquals(registered.id(), reopened.id());
        assertEquals(1, projects.findAll().size());
    }

    @Test
    void concurrentRegistrationByTheSameOwnerReopensOneProject() throws Exception {
        int attempts = 8;
        UUID ownerId = UUID.randomUUID();
        executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Project>> results = new ArrayList<>();
        for (int index = 0; index < attempts; index++) {
            int attempt = index;
            results.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return service.register(ownerId, "project-" + attempt, repository.toString(), List.of("mvn test"));
            }));
        }
        assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
        start.countDown();

        UUID projectId = null;
        for (Future<Project> result : results) {
            Project project = result.get();
            if (projectId == null) projectId = project.id();
            assertEquals(projectId, project.id());
        }
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
        Project reopened = register("root", repository);
        assertEquals(registered.id(), reopened.id());
    }

    private Project register(String name, Path path) {
        return service.register(fixtureOwner, name, path.toString(), List.of("mvn test"));
    }

    private void run(Path cwd, String... command) {
        ProcessRunner.ProcessResult result = new ProcessRunner().run(List.of(command), cwd, Map.of(), Duration.ofSeconds(20));
        if (result.exitCode() != 0) throw new AssertionError(result.stderr());
    }
}
