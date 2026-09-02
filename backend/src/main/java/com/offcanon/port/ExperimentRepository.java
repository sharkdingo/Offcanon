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
     * Returns whether a project has lifecycle work that must block changing
     * its verification policy. A sealed AGENT_COMPLETED result with a
     * re-runnable result is intentionally excluded: the user can configure or
     * correct commands before asking for verification. VERIFIED results are
     * also excluded because the project service invalidates them under the
     * project lock before saving a changed policy.
     */
    default boolean hasBlockingExperimentForProject(UUID projectId) {
        if (projectId == null) return false;
        return findByProjectId(projectId).stream().anyMatch(experiment -> switch (experiment.status()) {
            case CREATED, SNAPSHOTTING, READY_TO_RUN, RUNNING,
                    VERIFYING, PREPARING_PROMOTION,
                    PROMOTING, RECOVERY_REQUIRED -> true;
            case AGENT_COMPLETED -> experiment.resultSnapshotId() == null;
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
                    // The caller's own preparation/queue row is filtered out
                    // above. Any other row in those states still owns the
                    // session and must block a second lifecycle.
                    case CREATED, SNAPSHOTTING, READY_TO_RUN, RUNNING,
                            VERIFYING, PREPARING_PROMOTION,
                            PROMOTING, RECOVERY_REQUIRED -> true;
                    case AGENT_COMPLETED -> experiment.resultSnapshotId() == null;
                    default -> false;
                });
    }
}
