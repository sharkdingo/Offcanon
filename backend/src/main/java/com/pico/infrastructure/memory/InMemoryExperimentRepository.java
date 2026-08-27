package com.pico.infrastructure.memory;

import com.pico.experiment.domain.Experiment;
import com.pico.experiment.domain.ExperimentStatus;
import com.pico.port.ExperimentRepository;
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
        experiments.put(experiment.id(), experiment);
        return experiment;
    }

    @Override
    public Optional<Experiment> findById(UUID id) {
        return Optional.ofNullable(experiments.get(id));
    }

    @Override
    public List<Experiment> findByProjectId(UUID projectId) {
        return experiments.values().stream().filter(e -> e.projectId().equals(projectId))
                .sorted(Comparator.comparing(Experiment::createdAt)).toList();
    }

    @Override
    public boolean hasRunningExperiment(UUID sessionId) {
        return experiments.values().stream().anyMatch(e -> e.sessionId().equals(sessionId)
                && switch (e.status()) {
                    case RUNNING, AGENT_COMPLETED, VERIFYING -> true;
                    default -> false;
                });
    }
}
