package com.offcanon.infrastructure.verification;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.port.CommandExecutor;
import com.offcanon.port.EvidenceRepository;
import com.offcanon.port.VerificationPort;
import com.offcanon.port.SnapshotPort;
import com.offcanon.port.SnapshotRepository;
import com.offcanon.project.domain.Project;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.verification.domain.Evidence;
import com.offcanon.verification.domain.VerificationResult;
import com.offcanon.verification.domain.VerificationPurpose;
import com.offcanon.workspace.domain.Snapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class TrustedVerificationAdapter implements VerificationPort {
    private static final int MAX_OUTPUT_CHARS = 12_000;
    private final CommandExecutor commandExecutor;
    private final EvidenceRepository evidenceRepository;
    private final SnapshotPort snapshots;
    private final SnapshotRepository snapshotRepository;
    private final Duration timeout;

    public TrustedVerificationAdapter(CommandExecutor commandExecutor,
                                      EvidenceRepository evidenceRepository,
                                      SnapshotPort snapshots,
                                      SnapshotRepository snapshotRepository,
                                      @Value("${offcanon.agent.command-timeout-seconds:30}") long timeoutSeconds) {
        this.commandExecutor = commandExecutor;
        this.evidenceRepository = evidenceRepository;
        this.snapshots = snapshots;
        this.snapshotRepository = snapshotRepository;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    @Override
    public VerificationResult verify(Project project,
                                     Experiment experiment,
                                     Snapshot verifiedState,
                                     Path workspace,
                                     VerificationPurpose purpose) {
        if (project.verificationCommands().isEmpty()) {
            throw new DomainException("VERIFICATION_POLICY_MISSING", "Configure at least one verification command for the project");
        }
        List<VerificationResult.CommandEvidence> commands = new ArrayList<>();
        List<PendingEvidence> pendingEvidence = new ArrayList<>();
        VerificationResult result = null;
        for (String command : project.verificationCommands()) {
            Instant started = Instant.now();
            CommandExecutor.CommandExecution execution = commandExecutor.execute(command, workspace, timeout,
                    Map.of("OFFCANON_EXPERIMENT_ID", experiment.id().toString()), "trusted-verification");
            Instant completed = Instant.now();
            String stdout = truncate(execution.stdout());
            String stderr = truncate(execution.stderr());
            pendingEvidence.add(new PendingEvidence(command, workspace.toString(), execution.exitCode(), stdout, stderr,
                    started, completed, execution.duration(), execution.timedOut(), execution.cancelled(),
                    execution.environmentProfile()));
            commands.add(new VerificationResult.CommandEvidence(command, workspace.toString(),
                    execution.exitCode(), stdout, stderr, execution.duration(), execution.timedOut(), execution.cancelled()));
            if (execution.timedOut() || execution.cancelled() || execution.exitCode() != 0) {
                String reason = execution.cancelled() ? "Verification cancelled: " + command
                        : execution.timedOut() ? "Verification timed out: " + command : "Verification failed: " + command;
                result = VerificationResult.failed(commands, reason);
                break;
            }
        }
        if (result == null) result = VerificationResult.passed(commands);

        RuntimeException integrityFailure = null;
        boolean sourceUnchanged = false;
        try {
            Snapshot base = snapshotRepository.findById(experiment.baseSnapshotId())
                    .orElseThrow(() -> new DomainException("BASE_SNAPSHOT_MISSING",
                            "Base snapshot is unavailable during trusted verification"));
            String fingerprint = snapshots.fingerprintWorkspace(project, workspace, base.fingerprint());
            sourceUnchanged = verifiedState.fingerprint().equals(fingerprint);
            if (!sourceUnchanged) {
                integrityFailure = new DomainException("VERIFICATION_MUTATED_SOURCE",
                        "Trusted verification changed promotion-relevant files");
            }
        } catch (RuntimeException error) {
            integrityFailure = error;
        }
        for (PendingEvidence pending : pendingEvidence) {
            evidenceRepository.save(pending.toEvidence(experiment, verifiedState, purpose, sourceUnchanged));
        }
        if (integrityFailure != null) throw integrityFailure;
        return result;
    }

    private String truncate(String value) {
        if (value.length() <= MAX_OUTPUT_CHARS) return value;
        int head = MAX_OUTPUT_CHARS / 2;
        return value.substring(0, head) + "\n...[truncated]...\n" + value.substring(value.length() - head);
    }

    private record PendingEvidence(String command,
                                   String cwd,
                                   int exitCode,
                                   String stdout,
                                   String stderr,
                                   Instant startedAt,
                                   Instant completedAt,
                                   Duration duration,
                                   boolean timedOut,
                                   boolean cancelled,
                                   String environmentProfile) {
        private Evidence toEvidence(Experiment experiment,
                                    Snapshot verifiedState,
                                    VerificationPurpose purpose,
                                    boolean trusted) {
            return Evidence.verification(experiment.id(), verifiedState.id(), purpose, command, cwd,
                    exitCode, stdout, stderr, startedAt, completedAt, duration, timedOut, cancelled,
                    environmentProfile, trusted);
        }
    }
}
