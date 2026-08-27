package com.pico.verification.domain;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public record VerificationResult(boolean passed, List<CommandEvidence> commands, String failureReason) {
    public VerificationResult {
        commands = List.copyOf(commands);
        if (passed && failureReason != null) {
            throw new IllegalArgumentException("Passed verification cannot have a failure reason");
        }
    }

    public static VerificationResult passed(List<CommandEvidence> commands) {
        return new VerificationResult(true, commands, null);
    }

    public static VerificationResult failed(List<CommandEvidence> commands, String reason) {
        return new VerificationResult(false, commands, Objects.requireNonNull(reason, "reason"));
    }

    public record CommandEvidence(
            String command,
            String cwd,
            int exitCode,
            String stdout,
            String stderr,
            Duration duration,
            boolean timedOut,
            boolean cancelled) {
        public CommandEvidence {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(cwd, "cwd");
            Objects.requireNonNull(stdout, "stdout");
            Objects.requireNonNull(stderr, "stderr");
            Objects.requireNonNull(duration, "duration");
        }

        public CommandEvidence(String command,
                               String cwd,
                               int exitCode,
                               String stdout,
                               String stderr,
                               Duration duration,
                               boolean timedOut) {
            this(command, cwd, exitCode, stdout, stderr, duration, timedOut, false);
        }
    }
}
