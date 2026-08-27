package com.pico.infrastructure.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ProcessRunner {
    public ProcessResult run(List<String> command, Path cwd, Map<String, String> environment, Duration timeout) {
        long started = System.nanoTime();
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
            Map<String, String> safeEnvironment = new HashMap<>(builder.environment());
            safeEnvironment.keySet().removeIf(name -> {
                String upper = name.toUpperCase(Locale.ROOT);
                return upper.contains("API_KEY") || upper.contains("AUTH_TOKEN") || upper.contains("PASSWORD")
                        || upper.contains("SECRET") || upper.contains("CREDENTIAL");
            });
            safeEnvironment.putAll(environment);
            builder.environment().clear();
            builder.environment().putAll(safeEnvironment);
            process = builder.start();

            CompletableFuture<String> stdout = readAsync(process.getInputStream());
            CompletableFuture<String> stderr = readAsync(process.getErrorStream());
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            boolean timedOut = !finished;
            if (!finished) {
                process.toHandle().descendants().forEach(handle -> handle.destroyForcibly());
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            return new ProcessResult(
                    finished ? process.exitValue() : -1,
                    stdout.join(),
                    stderr.join(),
                    Duration.ofNanos(System.nanoTime() - started),
                    timedOut);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start process: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.toHandle().descendants().forEach(handle -> handle.destroyForcibly());
                process.destroyForcibly();
            }
            throw new IllegalStateException("Process interrupted: " + String.join(" ", command), e);
        }
    }

    private CompletableFuture<String> readAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                stream.transferTo(output);
                return output.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read process output", e);
            }
        });
    }

    public record ProcessResult(
            int exitCode,
            String stdout,
            String stderr,
            Duration duration,
            boolean timedOut) {
    }
}
