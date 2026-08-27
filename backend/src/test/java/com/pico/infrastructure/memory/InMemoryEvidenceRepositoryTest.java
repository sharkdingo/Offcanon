package com.pico.infrastructure.memory;

import com.pico.shared.domain.DomainException;
import com.pico.verification.domain.Evidence;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryEvidenceRepositoryTest {
    @Test
    void exactReplayIsIdempotentButConflictingIdentityIsRejected() {
        InMemoryEvidenceRepository repository = new InMemoryEvidenceRepository();
        UUID id = UUID.randomUUID();
        UUID experimentId = UUID.randomUUID();
        Evidence original = evidence(id, experimentId, "stdout");

        repository.save(original);
        repository.save(original);

        assertEquals(1, repository.findByExperimentId(experimentId).size());
        DomainException error = assertThrows(DomainException.class,
                () -> repository.save(evidence(id, experimentId, "different")));
        assertEquals("EVIDENCE_IDENTITY_CONFLICT", error.code());
    }

    private Evidence evidence(UUID id, UUID experimentId, String stdout) {
        Instant now = Instant.parse("2026-08-27T12:00:00Z");
        return new Evidence(id, experimentId, UUID.randomUUID(), "AGENT_COMMAND", "type file.txt",
                "workspace", 0, stdout, "", now, now.plusMillis(5), Duration.ofMillis(5),
                false, false, "agent-shell", false);
    }
}
