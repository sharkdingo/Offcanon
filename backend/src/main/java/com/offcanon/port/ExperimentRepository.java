package com.offcanon.port;

import com.offcanon.experiment.domain.Experiment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExperimentRepository {
    Experiment save(Experiment experiment);
    Optional<Experiment> findById(UUID id);
    List<Experiment> findByProjectId(UUID projectId);
    List<Experiment> findBySessionId(UUID sessionId);
    boolean hasRunningExperiment(UUID sessionId);

    /**
     * Returns whether a project has an experiment whose policy/evidence is
     * still mutable or whose promotion decision is not yet terminal. Project
     * verification settings must not change during that window.
     */
    default boolean hasActiveExperimentForProject(UUID projectId) {
        if (projectId == null) return false;
        return findByProjectId(projectId).stream().anyMatch(experiment -> switch (experiment.status()) {
            case CREATED, SNAPSHOTTING, READY_TO_RUN, RUNNING, AGENT_COMPLETED,
                    VERIFYING, VERIFIED, PREPARING_PROMOTION, PROMOTING,
                    RECOVERY_REQUIRED -> true;
            default -> false;
        });
    }

    /**
     * Same-session guard for a newly persisted READY_TO_RUN row. The row being
     * started must be excluded, otherwise the lifecycle would reject its own
     * transition after creation.
     */
    default boolean hasRunningExperiment(UUID sessionId, UUID excludingExperimentId) {
        return findBySessionId(sessionId).stream()
                .filter(experiment -> excludingExperimentId == null
                        || !experiment.id().equals(excludingExperimentId))
                .anyMatch(experiment -> switch (experiment.status()) {
                    // A READY_TO_RUN row is a queued, not yet claimed run. It
                    // must not prevent the first worker from claiming the
                    // session; once that worker is RUNNING, later starts are
                    // blocked. Creation/continuation use the broader guard.
                    case RUNNING, AGENT_COMPLETED, VERIFYING,
                            PREPARING_PROMOTION, PROMOTING, RECOVERY_REQUIRED -> true;
                    default -> false;
                });
    }
}
