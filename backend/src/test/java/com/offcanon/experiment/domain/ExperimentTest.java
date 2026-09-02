package com.offcanon.experiment.domain;

import com.offcanon.shared.domain.DomainException;
import com.offcanon.verification.domain.VerificationResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void passingPromotionVerificationCannotRejectAVerifiedResult() {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), Path.of("C:/offcanon/experiment"));
        experiment.start();
        experiment.markAgentCompleted("done");
        experiment.sealResult(UUID.randomUUID());
        experiment.beginVerification();
        experiment.markVerified(VerificationResult.passed(List.of()));

        DomainException error = assertThrows(DomainException.class,
                () -> experiment.rejectVerifiedPromotion(VerificationResult.passed(List.of())));

        assertEquals("INVALID_PROMOTION_REJECTION", error.code());
        assertEquals(ExperimentStatus.VERIFIED, experiment.status());
        assertTrue(experiment.verificationResult().passed());
    }

    @Test
    void aRejectedResultCanBeReverifiedAndPassingClearsTheOldFailure() {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), Path.of("C:/offcanon/experiment"));
        experiment.start();
        experiment.markAgentCompleted("done");
        experiment.sealResult(UUID.randomUUID());
        experiment.beginVerification();
        experiment.markVerified(VerificationResult.failed(List.of(), "test command failed"));

        experiment.beginVerification();
        assertEquals(ExperimentStatus.VERIFYING, experiment.status());
        assertNull(experiment.verificationResult());
        assertNull(experiment.failureReason());
        experiment.markVerified(VerificationResult.passed(List.of()));

        assertEquals(ExperimentStatus.VERIFIED, experiment.status());
        assertNull(experiment.failureReason());
    }

    @Test
    void changingAcceptancePolicyReturnsVerifiedResultToSealedWaitingState() {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), Path.of("C:/offcanon/experiment"));
        experiment.start();
        experiment.markAgentCompleted("done");
        UUID resultSnapshotId = UUID.randomUUID();
        experiment.sealResult(resultSnapshotId);
        experiment.beginVerification();
        experiment.markVerified(VerificationResult.passed(List.of()));

        experiment.invalidateVerificationForPolicyChange();

        assertEquals(ExperimentStatus.AGENT_COMPLETED, experiment.status());
        assertEquals(resultSnapshotId, experiment.resultSnapshotId());
        assertEquals("VERIFICATION_POLICY_CHANGED: acceptance commands changed; run verification again",
                experiment.failureReason());
        assertNull(experiment.verificationResult());
    }

    @Test
    void sealedResultRemainsReverifiableWhenBookkeepingFails() {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), Path.of("C:/offcanon/experiment"));
        experiment.start();
        experiment.markAgentCompleted("done");
        UUID resultSnapshotId = UUID.randomUUID();
        experiment.sealResult(resultSnapshotId);

        experiment.retainVerificationWaiting("verification bookkeeping failed");
        experiment.beginVerification();

        assertEquals(ExperimentStatus.VERIFYING, experiment.status());
        assertEquals(resultSnapshotId, experiment.resultSnapshotId());
    }

    @Test
    void interruptedVerificationReturnsToWaitingWithoutLosingTheSealedResult() {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), Path.of("C:/offcanon/experiment"));
        experiment.start();
        experiment.markAgentCompleted("done");
        UUID resultSnapshotId = UUID.randomUUID();
        experiment.sealResult(resultSnapshotId);
        experiment.beginVerification();

        experiment.recoverInterruptedVerification("verification interrupted");

        assertEquals(ExperimentStatus.AGENT_COMPLETED, experiment.status());
        assertEquals(resultSnapshotId, experiment.resultSnapshotId());
        assertEquals("verification interrupted", experiment.failureReason());
    }

    @Test
    void boundsDurableAgentSummaryBeforePersistence() {
        Experiment experiment = Experiment.create(UUID.randomUUID(), UUID.randomUUID(), "task", Instant.now());
        experiment.beginSnapshot();
        experiment.attachBase(UUID.randomUUID(), Path.of("C:/offcanon/experiment"));
        experiment.start();

        experiment.markAgentCompleted("结果".repeat(10_000));

        assertTrue(experiment.agentSummary().length() <= 12_000);
        assertTrue(experiment.agentSummary().endsWith("...[summary truncated]..."));
    }
}
