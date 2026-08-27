package com.offcanon.port;

import com.offcanon.verification.domain.Evidence;

import java.util.List;
import java.util.UUID;

public interface EvidenceRepository {
    Evidence save(Evidence evidence);
    List<Evidence> findByExperimentId(UUID experimentId);
}
