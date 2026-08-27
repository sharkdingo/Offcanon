package com.pico.port;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

public interface CommandExecutor {
    CommandExecution execute(String command, Path cwd, Duration timeout, Map<String, String> environment);

    /**
     * Executes a command under a named environment policy. Existing adapters can
     * keep implementing the four-argument method; the profile is still surfaced
     * as "controlled" until they opt into this overload.
     */
    default CommandExecution execute(String command,
                                     Path cwd,
                                     Duration timeout,
                                     Map<String, String> environment,
                                     String environmentProfile) {
        return execute(command, cwd, timeout, environment);
    }

    record CommandExecution(int exitCode,
                            String stdout,
                            String stderr,
                            Duration duration,
                            boolean timedOut,
                            boolean cancelled,
                            String environmentProfile) {
        public CommandExecution(int exitCode,
                                String stdout,
                                String stderr,
                                Duration duration,
                                boolean timedOut) {
            this(exitCode, stdout, stderr, duration, timedOut, false, "controlled");
        }

        public CommandExecution {
            environmentProfile = environmentProfile == null || environmentProfile.isBlank()
                    ? "controlled" : environmentProfile;
        }
    }
}
