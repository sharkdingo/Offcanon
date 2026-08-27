package com.pico.infrastructure.memory;

import com.pico.port.EvidenceRepository;
import com.pico.verification.domain.Evidence;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
@Profile("!mysql")
public class InMemoryEvidenceRepository implements EvidenceRepository {
    private final CopyOnWriteArrayList<Evidence> evidence = new CopyOnWriteArrayList<>();

    @Override
    public Evidence save(Evidence item) {
        evidence.add(item);
        return item;
    }

    @Override
    public List<Evidence> findByExperimentId(UUID experimentId) {
        return evidence.stream().filter(item -> item.experimentId().equals(experimentId))
                .sorted(Comparator.comparing(Evidence::startedAt)).toList();
    }
}
