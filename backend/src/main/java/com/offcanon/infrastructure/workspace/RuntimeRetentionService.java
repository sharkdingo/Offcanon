package com.offcanon.infrastructure.workspace;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.port.ExperimentRepository;
import com.offcanon.port.ProjectRepository;
import com.offcanon.port.PromotionJournalPort;
import com.offcanon.promotion.domain.PromotionJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Best-effort retention for physical runtime materializations.
 *
 * <p>Snapshots and lifecycle rows are durable history. Their materialized
 * directories are cache-like and can be evicted once no experiment or open
 * promotion journal still needs them. The cleaner never removes canonical
 * project files and never deletes a database row.</p>
 */
@Component
public class RuntimeRetentionService {
    private static final Logger log = LoggerFactory.getLogger(RuntimeRetentionService.class);
    private static final Set<ExperimentStatus> ACTIVE_EXPERIMENTS = EnumSet.of(
            ExperimentStatus.CREATED,
            ExperimentStatus.SNAPSHOTTING,
            ExperimentStatus.READY_TO_RUN,
            ExperimentStatus.RUNNING,
            ExperimentStatus.AGENT_COMPLETED,
            ExperimentStatus.VERIFYING,
            ExperimentStatus.PREPARING_PROMOTION,
            ExperimentStatus.PROMOTING,
            ExperimentStatus.RECOVERY_REQUIRED);
    private static final Set<ExperimentStatus> FORK_SOURCE_STATUSES = EnumSet.of(
            ExperimentStatus.FAILED,
            ExperimentStatus.REJECTED,
            ExperimentStatus.STALE,
            ExperimentStatus.CANCELLED);
    private final Path dataRoot;
    private final ProjectRepository projects;
    private final ExperimentRepository experiments;
    private final PromotionJournalPort promotionJournals;
    private final Duration orphanRetention;
    private final Duration verificationRetention;
    private final Duration promotionRetention;
    private final Duration snapshotRetention;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean applicationReady = new AtomicBoolean();

    @Autowired
    public RuntimeRetentionService(
            @Value("${offcanon.data-root}") String dataRoot,
            ProjectRepository projects,
            ExperimentRepository experiments,
            PromotionJournalPort promotionJournals,
            @Value("${offcanon.runtime.orphan-retention-hours:24}") long orphanRetentionHours,
            @Value("${offcanon.runtime.verification-retention-hours:24}") long verificationRetentionHours,
            @Value("${offcanon.runtime.promotion-retention-hours:24}") long promotionRetentionHours,
            @Value("${offcanon.runtime.snapshot-retention-hours:168}") long snapshotRetentionHours) {
        this(Path.of(dataRoot), projects, experiments, promotionJournals,
                hours(orphanRetentionHours), hours(verificationRetentionHours),
                hours(promotionRetentionHours), hours(snapshotRetentionHours));
    }

    /** Constructor used by focused retention tests and embedded runtimes. */
    public RuntimeRetentionService(Path dataRoot,
                                   ProjectRepository projects,
                                   ExperimentRepository experiments,
                                   PromotionJournalPort promotionJournals,
                                   Duration orphanRetention,
                                   Duration verificationRetention,
                                   Duration promotionRetention,
                                   Duration snapshotRetention) {
        this.dataRoot = dataRoot.toAbsolutePath().normalize();
        this.projects = projects;
        this.experiments = experiments;
        this.promotionJournals = promotionJournals;
        this.orphanRetention = normalize(orphanRetention);
        this.verificationRetention = normalize(verificationRetention);
        this.promotionRetention = normalize(promotionRetention);
        this.snapshotRetention = normalize(snapshotRetention);
    }

    /**
     * The MySQL schema is installed by a CommandLineRunner. Do not let the
     * scheduler race that initializer during application startup; run one
     * cleanup as soon as all startup runners have completed instead.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void cleanupOnApplicationReady() {
        applicationReady.set(true);
        cleanup(Instant.now());
    }

    @Scheduled(fixedDelayString = "${offcanon.runtime.retention-interval-ms:3600000}")
    public void scheduledCleanup() {
        if (!applicationReady.get()) return;
        cleanup(Instant.now());
    }

    /** Runs one idempotent best-effort cleanup pass. */
    public CleanupReport cleanup(Instant now) {
        if (!running.compareAndSet(false, true)) {
            return CleanupReport.empty();
        }
        try {
            RetentionContext context;
            try {
                context = loadContext();
            } catch (RuntimeException error) {
                // Protection data is authoritative. If it cannot be read, do
                // not guess and risk removing an artifact still needed for a
                // running experiment or recovery journal.
                log.warn("Runtime retention deferred because lifecycle state could not be loaded: {}",
                        error.getMessage());
                return CleanupReport.empty();
            }
            CleanupCounter counter = new CleanupCounter();
            cleanupVerificationWorkspaces(context, now, counter);
            cleanupPromotionCandidates(context, now, counter);
            cleanupExperimentWorkspaces(context, now, counter);
            cleanupSnapshots(context, now, counter);
            CleanupReport report = counter.report();
            if (report.total() > 0) {
                log.info("Runtime retention removed {} verification workspaces, {} promotion candidates, "
                                + "{} experiment workspaces, and {} snapshot materializations",
                        report.verificationWorkspaces(), report.promotionCandidates(),
                        report.experimentWorkspaces(), report.snapshotMaterializations());
            }
            return report;
        } finally {
            running.set(false);
        }
    }

    private RetentionContext loadContext() {
        Map<UUID, Experiment> known = new HashMap<>();
        for (var project : projects.findAll()) {
            for (Experiment experiment : experiments.findByProjectId(project.id())) {
                known.put(experiment.id(), experiment);
            }
        }
        Set<UUID> protectedSnapshots = new HashSet<>();
        Set<UUID> activeExperiments = new HashSet<>();
        Set<UUID> recoveryExperiments = new HashSet<>();
        Set<UUID> forkReadySources = new HashSet<>();
        for (Experiment experiment : known.values()) {
            if (experiment.baseSnapshotId() != null) protectedSnapshots.add(experiment.baseSnapshotId());
            if (experiment.resultSnapshotId() != null) protectedSnapshots.add(experiment.resultSnapshotId());
            if (ACTIVE_EXPERIMENTS.contains(experiment.status())) activeExperiments.add(experiment.id());
            if (experiment.status() == ExperimentStatus.RECOVERY_REQUIRED) recoveryExperiments.add(experiment.id());
            if (experiment.continuedFromExperimentId() != null
                    && experiment.baseSnapshotId() != null
                    && experiment.workspacePath() != null
                    && Files.isDirectory(experiment.workspacePath(), LinkOption.NOFOLLOW_LINKS)) {
                forkReadySources.add(experiment.continuedFromExperimentId());
            }
        }
        Set<Path> protectedCandidates = new HashSet<>();
        List<PromotionJournal> open = promotionJournals.findOpen();
        if (open != null) {
            for (PromotionJournal journal : open) {
                protectedCandidates.add(normalize(journal.candidatePath()));
            }
        }
        // findOpen intentionally excludes RECOVERY_REQUIRED; unresolved
        // journals still own their candidate until manual reconciliation.
        for (var project : projects.findAll()) {
            List<PromotionJournal> unresolved = promotionJournals.findUnresolvedByProject(project.id());
            if (unresolved == null) continue;
            for (PromotionJournal journal : unresolved) {
                protectedCandidates.add(normalize(journal.candidatePath()));
            }
        }
        return new RetentionContext(known, protectedSnapshots, activeExperiments,
                recoveryExperiments, forkReadySources, protectedCandidates);
    }

    private void cleanupVerificationWorkspaces(RetentionContext context,
                                                Instant now,
                                                CleanupCounter counter) {
        Path root = managedRoot("verification-workspaces");
        for (Path experimentRoot : children(root)) {
            UUID experimentId = parseUuid(experimentRoot.getFileName());
            if (experimentId != null && context.activeExperiments().contains(experimentId)) continue;
            for (Path attempt : children(experimentRoot)) {
                if (!isOld(attempt, now, verificationRetention)) continue;
                if (deleteManaged(attempt, root)) counter.verificationWorkspaces++;
            }
            deleteIfEmpty(experimentRoot, root);
        }
    }

    private void cleanupPromotionCandidates(RetentionContext context,
                                             Instant now,
                                             CleanupCounter counter) {
        Path root = managedRoot("promotion-candidates");
        for (Path experimentRoot : children(root)) {
            UUID experimentId = parseUuid(experimentRoot.getFileName());
            if (experimentId != null && context.recoveryExperiments().contains(experimentId)) continue;
            for (Path candidate : children(experimentRoot)) {
                if (isProtected(candidate, context.protectedCandidates())) continue;
                if (!isOld(candidate, now, promotionRetention)) continue;
                if (deleteManaged(candidate, root)) counter.promotionCandidates++;
            }
            deleteIfEmpty(experimentRoot, root);
        }
    }

    private void cleanupExperimentWorkspaces(RetentionContext context,
                                             Instant now,
                                             CleanupCounter counter) {
        Path root = managedRoot("experiments");
        for (Path workspace : children(root)) {
            UUID experimentId = parseUuid(workspace.getFileName());
            Experiment experiment = experimentId == null ? null : context.known().get(experimentId);
            if (experiment != null) {
                // A sealed result is the durable source for diff, promotion and
                // continuation. Once the run is terminal, its mutable working
                // tree is only a cache and can follow the normal workspace TTL.
                // Runs without a sealed result may still contain the only useful
                // partial draft, so retain them until a successor has forked it.
                boolean terminalResult = experiment.resultSnapshotId() != null
                        && !ACTIVE_EXPERIMENTS.contains(experiment.status());
                boolean forkedPartial = experiment.resultSnapshotId() == null
                        && FORK_SOURCE_STATUSES.contains(experiment.status())
                        && context.forkReadySources().contains(experiment.id());
                boolean eligible = forkedPartial || (terminalResult && isOld(workspace, now, orphanRetention));
                if (eligible && deleteManaged(workspace, root)) {
                    counter.experimentWorkspaces++;
                }
                continue;
            }
            if (isOld(workspace, now, orphanRetention) && deleteManaged(workspace, root)) {
                counter.experimentWorkspaces++;
            }
        }
    }

    private void cleanupSnapshots(RetentionContext context,
                                  Instant now,
                                  CleanupCounter counter) {
        Path root = managedRoot("snapshots");
        for (Path snapshot : children(root)) {
            UUID snapshotId = parseUuid(snapshot.getFileName());
            if (snapshotId == null || context.protectedSnapshots().contains(snapshotId)) continue;
            if (!isOld(snapshot, now, snapshotRetention)) continue;
            if (deleteManaged(snapshot, root)) counter.snapshotMaterializations++;
        }
    }

    private Path managedRoot(String child) {
        return dataRoot.resolve(child).toAbsolutePath().normalize();
    }

    private List<Path> children(Path root) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var stream = Files.list(root)) {
            return stream.filter(path -> !Files.isSymbolicLink(path)).toList();
        } catch (IOException error) {
            log.debug("Unable to list runtime retention directory {}: {}", root, error.getMessage());
            return List.of();
        }
    }

    private boolean isOld(Path path, Instant now, Duration retention) {
        try {
            return !Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant()
                    .isAfter(now.minus(retention));
        } catch (IOException error) {
            return false;
        }
    }

    private boolean isProtected(Path candidate, Set<Path> protectedPaths) {
        Path normalized = normalize(candidate);
        return protectedPaths.stream().anyMatch(path -> normalized.equals(path)
                || normalized.startsWith(path)
                || path.startsWith(normalized));
    }

    private boolean deleteManaged(Path path, Path managedRoot) {
        Path normalized = normalize(path);
        Path root = normalize(managedRoot);
        if (normalized.equals(root) || !normalized.startsWith(root)
                || Files.isSymbolicLink(path)) return false;
        try {
            deleteTree(path);
            return true;
        } catch (IOException error) {
            log.debug("Unable to remove expired runtime path {}: {}", path, error.getMessage());
            return false;
        }
    }

    private void deleteIfEmpty(Path path, Path managedRoot) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return;
        if (!children(path).isEmpty()) return;
        deleteManaged(path, managedRoot);
    }

    private void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private UUID parseUuid(Path fileName) {
        if (fileName == null) return null;
        try {
            return UUID.fromString(fileName.toString());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static Duration hours(long value) {
        return Duration.ofHours(Math.max(0, value));
    }

    private static Duration normalize(Duration value) {
        return value == null || value.isNegative() ? Duration.ZERO : value;
    }

    private record RetentionContext(Map<UUID, Experiment> known,
                                    Set<UUID> protectedSnapshots,
                                    Set<UUID> activeExperiments,
                                    Set<UUID> recoveryExperiments,
                                    Set<UUID> forkReadySources,
                                    Set<Path> protectedCandidates) {
    }

    public record CleanupReport(int verificationWorkspaces,
                                int promotionCandidates,
                                int experimentWorkspaces,
                                int snapshotMaterializations) {
        static CleanupReport empty() {
            return new CleanupReport(0, 0, 0, 0);
        }

        public int total() {
            return verificationWorkspaces + promotionCandidates + experimentWorkspaces + snapshotMaterializations;
        }
    }

    private static final class CleanupCounter {
        private int verificationWorkspaces;
        private int promotionCandidates;
        private int experimentWorkspaces;
        private int snapshotMaterializations;

        private CleanupReport report() {
            return new CleanupReport(verificationWorkspaces, promotionCandidates,
                    experimentWorkspaces, snapshotMaterializations);
        }
    }
}
