package com.offcanon.infrastructure.memory;

import com.offcanon.port.EvidenceRepository;
import com.offcanon.verification.domain.Evidence;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.offcanon.shared.domain.DomainException;
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
