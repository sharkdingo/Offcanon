package com.offcanon.agent.application;

import com.offcanon.agent.domain.RunEvent;
import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.port.EventSink;
import com.offcanon.port.ExperimentRepository;
import com.offcanon.port.ProjectRepository;
import com.offcanon.port.SessionRunLeasePort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Settles agent runs that survived in persistent state but lost their worker
 * process.  A run is recovered only after the session lease can be acquired;
 * an active lease therefore keeps a live worker untouched, including during a
 * rolling restart of another application instance.
 */
@Component
public class AgentRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(AgentRecoveryService.class);
    private static final Set<ExperimentStatus> INTERRUPTIBLE = EnumSet.of(
            ExperimentStatus.RUNNING,
            ExperimentStatus.AGENT_COMPLETED,
            ExperimentStatus.VERIFYING);
    private static final Set<ExperimentStatus> INITIALIZATION_INTERRUPTED = EnumSet.of(
            ExperimentStatus.CREATED,
            ExperimentStatus.SNAPSHOTTING);
    private static final String RESTART_REASON =
            "Agent worker was interrupted by an application restart; continue the experiment to resume from its durable result or draft";

    private final ProjectRepository projects;
    private final ExperimentRepository experiments;
    private final SessionRunLeasePort leases;
    private final EventSink events;
    private final AtomicBoolean applicationReady = new AtomicBoolean();

    @Autowired
    public AgentRecoveryService(ProjectRepository projects,
                                ExperimentRepository experiments,
                                SessionRunLeasePort leases,
                                EventSink events) {
        this.projects = projects;
        this.experiments = experiments;
        this.leases = leases;
        this.events = events;
    }

    /** Run one pass after schema/repository initialization has completed. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        recoverInterruptedInitialization();
        recoverInterruptedRuns();
        applicationReady.set(true);
    }

    /** A Redis lease may still be alive during restart; retry after it expires. */
    @Scheduled(fixedDelayString = "${offcanon.agent.recovery-interval-ms:30000}")
    public void recoverExpiredRuns() {
        if (!applicationReady.get()) return;
        recoverInterruptedInitialization();
        recoverInterruptedRuns();
    }

    /**
     * Performs an idempotent recovery pass.  The method is public for focused
     * tests and embedded runtimes; callers should invoke it after persistence
     * is ready.
     */
    public int recoverInterruptedRuns() {
        return recoverCandidates(INTERRUPTIBLE, RESTART_REASON);
    }

    /**
     * A crash during synchronous experiment creation can leave a CREATED or
     * SNAPSHOTTING row that no worker can ever start. Resolve those rows after
     * the creator lease is no longer held so they remain continuable.
     */
    public int recoverInterruptedInitialization() {
        return recoverCandidates(INITIALIZATION_INTERRUPTED,
                "Experiment initialization was interrupted by an application restart; continue to create a fresh snapshot");
    }

    private int recoverCandidates(Set<ExperimentStatus> recoverable,
                                  String reason) {
        int recovered = 0;
        final List<com.offcanon.project.domain.Project> knownProjects;
        try {
            knownProjects = projects.findAll();
        } catch (RuntimeException error) {
            log.warn("Agent recovery deferred because projects could not be loaded: {}", error.getMessage());
            return 0;
        }
        for (var project : knownProjects) {
            final List<Experiment> knownExperiments;
            try {
                knownExperiments = experiments.findByProjectId(project.id());
            } catch (RuntimeException error) {
                log.warn("Agent recovery deferred for project {}: {}", project.id(), error.getMessage());
                continue;
            }
            for (Experiment candidate : knownExperiments) {
                if (!recoverable.contains(candidate.status())) continue;
                SessionRunLeasePort.Lease lease;
                try {
                    lease = leases.tryAcquire(candidate.sessionId(), candidate.id()).orElse(null);
                } catch (RuntimeException error) {
                    log.warn("Agent recovery deferred for experiment {}: {}", candidate.id(), error.getMessage());
                    continue;
                }
                if (lease == null) continue;
                try {
                    Experiment current;
                    try {
                        lease.assertHeld();
                        current = experiments.findById(candidate.id()).orElse(null);
                    } catch (RuntimeException error) {
                        log.warn("Agent recovery deferred for experiment {}: {}", candidate.id(), error.getMessage());
                        continue;
                    }
                    if (current == null || !recoverable.contains(current.status())) continue;
                    try {
                        lease.assertHeld();
                        current.fail(reason);
                        lease.assertHeld();
                        experiments.save(current);
                        recovered++;
                        publish(current.id(), "EXPERIMENT_RECOVERED", java.util.Map.of(
                                "status", current.status().name(),
                                "reason", reason));
                    } catch (RuntimeException error) {
                        // A concurrent worker/state transition is authoritative,
                        // but persistence or invariant failures must remain
                        // visible before a later pass retries the row.
                        log.warn("Unable to settle interrupted experiment {}: {}",
                                candidate.id(), error.getMessage());
                    }
                } finally {
                    try {
                        lease.release();
                    } catch (RuntimeException error) {
                        log.warn("Unable to release agent recovery lease for {}: {}", candidate.id(), error.getMessage());
                    }
                }
            }
        }
        return recovered;
    }

    private void publish(UUID experimentId, String type, java.util.Map<String, Object> payload) {
        if (events == null) return;
        try {
            RunEvent ignored = events.publish(experimentId, type, payload);
        } catch (RuntimeException ignored) {
            // Lifecycle state remains authoritative if telemetry is unavailable.
        }
    }
}
