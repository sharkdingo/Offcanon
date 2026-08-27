package com.pico.port;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

public interface CommandExecutor {
    CommandExecution execute(String command, Path cwd, Duration timeout, Map<String, String> environment);

    record CommandExecution(int exitCode, String stdout, String stderr, Duration duration, boolean timedOut) {
    }
}
