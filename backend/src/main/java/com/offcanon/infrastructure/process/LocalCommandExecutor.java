package com.offcanon.infrastructure.process;

import com.offcanon.port.CommandExecutor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
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
    public CommandExecution execute(String command,
                                    Path cwd,
                                    Duration timeout,
                                    Map<String, String> environment,
                                    String environmentProfile) {
        Map<String, String> boundedEnvironment = new HashMap<>(environment);
        Path workspace = cwd.toAbsolutePath().normalize();
        if (workspace.getParent() != null) {
            boundedEnvironment.put("GIT_CEILING_DIRECTORIES", workspace.getParent().toString());
        }
        List<String> processCommand = isWindows()
                ? List.of("cmd.exe", "/d", "/s", "/c", command)
                // Do not load the user's login profile for an agent command.
                // It can execute arbitrary startup code outside the workspace;
                // ProcessRunner still supplies the explicit bounded environment.
                : List.of("sh", "-c", command);
        ProcessRunner.ProcessResult result = processRunner.run(processCommand, cwd, boundedEnvironment, timeout);
        return new CommandExecution(result.exitCode(), result.stdout(), result.stderr(), result.duration(),
                result.timedOut(), result.cancelled(), environmentProfile);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
