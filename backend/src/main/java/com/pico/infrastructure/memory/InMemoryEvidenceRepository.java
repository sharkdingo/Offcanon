package com.pico.infrastructure.memory;

import com.pico.port.EvidenceRepository;
import com.pico.verification.domain.Evidence;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.pico.shared.domain.DomainException;

@Repository
@Profile("!mysql")
public class InMemoryEvidenceRepository implements EvidenceRepository {
    private final ConcurrentHashMap<UUID, Evidence> evidence = new ConcurrentHashMap<>();

    @Override
    public Evidence save(Evidence item) {
        Evidence stored = evidence.putIfAbsent(item.id(), item);
        if (stored == null || stored.equals(item)) return item;
        throw new DomainException("EVIDENCE_IDENTITY_CONFLICT",
                "Evidence identity already belongs to different content: " + item.id());
    }

    @Override
    public List<Evidence> findByExperimentId(UUID experimentId) {
        return evidence.values().stream().filter(item -> item.experimentId().equals(experimentId))
                .sorted(Comparator.comparing(Evidence::startedAt)).toList();
    }
}
