package com.offcanon.infrastructure.process;

import com.offcanon.port.CommandExecutor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class LocalCommandExecutor implements CommandExecutor {
    private final ProcessRunner processRunner;

    public LocalCommandExecutor(ProcessRunner processRunner) {
        this.processRunner = processRunner;
    }

    @Override
    public CommandExecution execute(String command, Path cwd, Duration timeout, Map<String, String> environment) {
        return execute(command, cwd, timeout, environment, "controlled-process");
    }

    @Override
    public CommandExecution execute(String command,
                                    Path cwd,
                                    Duration timeout,
                                    Map<String, String> environment,
                                    String environmentProfile) {
        List<String> processCommand = isWindows()
                ? List.of("cmd.exe", "/d", "/s", "/c", command)
                : List.of("sh", "-lc", command);
        ProcessRunner.ProcessResult result = processRunner.run(processCommand, cwd, environment, timeout);
        return new CommandExecution(result.exitCode(), result.stdout(), result.stderr(), result.duration(),
                result.timedOut(), result.cancelled(), environmentProfile);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
