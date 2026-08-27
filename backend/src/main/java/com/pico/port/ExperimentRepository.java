package com.pico.port;

import com.pico.experiment.domain.Experiment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExperimentRepository {
    Experiment save(Experiment experiment);
    Optional<Experiment> findById(UUID id);
    List<Experiment> findByProjectId(UUID projectId);
    List<Experiment> findBySessionId(UUID sessionId);
    boolean hasRunningExperiment(UUID sessionId);
}
