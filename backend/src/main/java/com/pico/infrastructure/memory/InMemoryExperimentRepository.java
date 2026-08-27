package com.pico.infrastructure.memory;

import com.pico.experiment.domain.Experiment;
import com.pico.experiment.domain.ExperimentStatus;
import com.pico.port.ExperimentRepository;
import com.pico.shared.domain.DomainException;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("!mysql")
public class InMemoryExperimentRepository implements ExperimentRepository {
    private final ConcurrentHashMap<UUID, Experiment> experiments = new ConcurrentHashMap<>();

    @Override
    public Experiment save(Experiment experiment) {
        Experiment stored = experiments.compute(experiment.id(), (id, current) -> {
            if (current == null) {
                if (experiment.version() != 0) throw versionConflict(experiment);
            } else if (experiment.version() != current.version() + 1) {
                throw versionConflict(experiment);
            }
            return copy(experiment);
        });
        return copy(stored);
    }

    @Override
    public Optional<Experiment> findById(UUID id) {
        return Optional.ofNullable(experiments.get(id)).map(this::copy);
    }

    @Override
    public List<Experiment> findByProjectId(UUID projectId) {
        return experiments.values().stream().filter(e -> e.projectId().equals(projectId))
                .sorted(Comparator.comparing(Experiment::createdAt)).map(this::copy).toList();
    }

    @Override
    public List<Experiment> findBySessionId(UUID sessionId) {
        return experiments.values().stream().filter(e -> e.sessionId().equals(sessionId))
                .sorted(Comparator.comparing(Experiment::createdAt)).map(this::copy).toList();
    }

    @Override
    public boolean hasRunningExperiment(UUID sessionId) {
        return experiments.values().stream().anyMatch(e -> e.sessionId().equals(sessionId)
                && switch (e.status()) {
                    case RUNNING, AGENT_COMPLETED, VERIFYING -> true;
                    default -> false;
                });
    }

    private Experiment copy(Experiment experiment) {
        return Experiment.restore(experiment.id(), experiment.projectId(), experiment.sessionId(), experiment.task(),
                experiment.createdAt(), experiment.status(), experiment.baseSnapshotId(), experiment.resultSnapshotId(),
                experiment.workspacePath(), experiment.agentSummary(), experiment.verificationResult(),
                experiment.failureReason(), experiment.version());
    }

    private DomainException versionConflict(Experiment experiment) {
        return new DomainException("EXPERIMENT_VERSION_CONFLICT",
                "Experiment changed concurrently: " + experiment.id());
    }
}
