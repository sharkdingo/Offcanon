package com.offcanon.infrastructure.memory;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.shared.domain.DomainException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
