package com.offcanon.experiment.domain;

import com.offcanon.shared.domain.DomainException;
import com.offcanon.verification.domain.VerificationResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExperimentTest {
    @Test
    void onlyTrustedVerificationCanReachVerified() {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "fix bug", Instant.now());

        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), Path.of("C:/offcanon/experiment"));
        experiment.start();
        experiment.markAgentCompleted("model stopped after tests");

        assertThrows(DomainException.class, experiment::beginPromotion);

        experiment.sealResult(UUID.randomUUID());
        experiment.beginVerification();
        experiment.markVerified(VerificationResult.passed(List.of()));

        assertEquals(ExperimentStatus.VERIFIED, experiment.status());
        experiment.beginPromotion();
        assertEquals(ExperimentStatus.PREPARING_PROMOTION, experiment.status());
    }

    @Test
    void baseSnapshotCannotBeReplaced() {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), Path.of("C:/offcanon/experiment"));

        assertThrows(DomainException.class,
                () -> experiment.attachBase(UUID.randomUUID(), Path.of("C:/offcanon/other")));
    }

    @Test
    void failedVerificationIsRejected() {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), Path.of("C:/offcanon/experiment"));
        experiment.start();
        experiment.markAgentCompleted("done");
        experiment.sealResult(UUID.randomUUID());
        experiment.beginVerification();
        experiment.markVerified(VerificationResult.failed(List.of(), "test command failed"));

        assertEquals(ExperimentStatus.REJECTED, experiment.status());
        assertEquals("test command failed", experiment.failureReason());
    }
}
