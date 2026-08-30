package com.offcanon.infrastructure.memory;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.shared.domain.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class InMemoryExperimentRepositoryTest {
    @Test
    void rejectsAStaleDetachedSave() {
        InMemoryExperimentRepository repository = new InMemoryExperimentRepository();
        Experiment created = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        repository.save(created);
        Experiment first = repository.findById(created.id()).orElseThrow();
        Experiment stale = repository.findById(created.id()).orElseThrow();

        first.beginSnapshot();
        repository.save(first);
        stale.beginSnapshot();

        DomainException error = assertThrows(DomainException.class, () -> repository.save(stale));

        assertEquals("EXPERIMENT_VERSION_CONFLICT", error.code());
    }

    @Test
    void returnsDetachedCopiesInsteadOfSharedMutableState() {
        InMemoryExperimentRepository repository = new InMemoryExperimentRepository();
        Experiment created = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        repository.save(created);

        Experiment detached = repository.findById(created.id()).orElseThrow();
        detached.beginSnapshot();
        detached.attachBase(UUID.randomUUID(), Path.of("C:/offcanon/workspace"));

        assertEquals(0, repository.findById(created.id()).orElseThrow().version());
    }

    @ParameterizedTest
    @EnumSource(value = ExperimentStatus.class, names = {
            "CREATED", "SNAPSHOTTING", "READY_TO_RUN", "RUNNING", "AGENT_COMPLETED",
            "VERIFYING", "PREPARING_PROMOTION", "PROMOTING", "RECOVERY_REQUIRED"})
    void lifecycleStatesBlockAnotherExperimentInTheSameSession(ExperimentStatus status) {
        InMemoryExperimentRepository repository = new InMemoryExperimentRepository();
        UUID sessionId = UUID.randomUUID();
        Experiment active = Experiment.restore(UUID.randomUUID(), UUID.randomUUID(), sessionId,
                "task", Instant.now(), status, UUID.randomUUID(), UUID.randomUUID(),
                Path.of("C:/offcanon/workspace"), null, null, "recovery", 0);
        repository.save(active);

        assertTrue(repository.hasRunningExperiment(sessionId));
    }

    @Test
    void terminalStatesDoNotBlockAnotherExperiment() {
        InMemoryExperimentRepository repository = new InMemoryExperimentRepository();
        UUID sessionId = UUID.randomUUID();
        Experiment terminal = Experiment.restore(UUID.randomUUID(), UUID.randomUUID(), sessionId,
                "task", Instant.now(), ExperimentStatus.PROMOTED, UUID.randomUUID(), UUID.randomUUID(),
                Path.of("C:/offcanon/workspace"), null, null, null, 0);
        repository.save(terminal);

        assertFalse(repository.hasRunningExperiment(sessionId));
    }
}
