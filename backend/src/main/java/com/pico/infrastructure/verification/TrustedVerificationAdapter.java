package com.pico.infrastructure.verification;

import com.pico.experiment.domain.Experiment;
import com.pico.port.CommandExecutor;
import com.pico.port.EvidenceRepository;
import com.pico.port.VerificationPort;
import com.pico.project.domain.Project;
import com.pico.shared.domain.DomainException;
import com.pico.verification.domain.Evidence;
import com.pico.verification.domain.VerificationResult;
import com.pico.workspace.domain.Snapshot;
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
    private final Duration timeout;

    public TrustedVerificationAdapter(CommandExecutor commandExecutor,
                                      EvidenceRepository evidenceRepository,
                                      @Value("${pico.agent.command-timeout-seconds:30}") long timeoutSeconds) {
        this.commandExecutor = commandExecutor;
        this.evidenceRepository = evidenceRepository;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    @Override
    public VerificationResult verify(Project project, Experiment experiment, Snapshot snapshot) {
        if (project.verificationCommands().isEmpty()) {
            throw new DomainException("VERIFICATION_POLICY_MISSING", "Configure at least one verification command for the project");
        }
        List<VerificationResult.CommandEvidence> commands = new ArrayList<>();
        for (String command : project.verificationCommands()) {
            Instant started = Instant.now();
            CommandExecutor.CommandExecution execution = commandExecutor.execute(command, experiment.workspacePath(), timeout,
                    Map.of("PICO_EXPERIMENT_ID", experiment.id().toString()));
            Instant completed = Instant.now();
            String stdout = truncate(execution.stdout());
            String stderr = truncate(execution.stderr());
            evidenceRepository.save(Evidence.verification(experiment.id(), snapshot.id(), command,
                    experiment.workspacePath().toString(), execution.exitCode(), stdout, stderr,
                    started, completed, execution.duration(), execution.timedOut()));
            commands.add(new VerificationResult.CommandEvidence(command, experiment.workspacePath().toString(),
                    execution.exitCode(), stdout, stderr, execution.duration(), execution.timedOut()));
            if (execution.timedOut() || execution.exitCode() != 0) {
                String reason = execution.timedOut() ? "Verification timed out: " + command : "Verification failed: " + command;
                return VerificationResult.failed(commands, reason);
            }
        }
        return VerificationResult.passed(commands);
    }

    private String truncate(String value) {
        if (value.length() <= MAX_OUTPUT_CHARS) return value;
        int head = MAX_OUTPUT_CHARS / 2;
        return value.substring(0, head) + "\n...[truncated]...\n" + value.substring(value.length() - head);
    }
}
