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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

public class ProcessRunner {
    private static final int MAX_CAPTURE_BYTES = 1_000_000;
    private static final int HEAD_CAPTURE_BYTES = MAX_CAPTURE_BYTES / 2;
    private static final int TAIL_CAPTURE_BYTES = MAX_CAPTURE_BYTES - HEAD_CAPTURE_BYTES;

    public ProcessResult run(List<String> command, Path cwd, Map<String, String> environment, Duration timeout) {
        long started = System.nanoTime();
        Process process = null;
        CompletableFuture<String> stdout = CompletableFuture.completedFuture("");
        CompletableFuture<String> stderr = CompletableFuture.completedFuture("");
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
            Map<String, String> safeEnvironment = new HashMap<>(builder.environment());
            safeEnvironment.keySet().removeIf(this::isSensitiveName);
            environment.forEach((name, value) -> {
                if (!isSensitiveName(name)) {
                    safeEnvironment.put(name, value);
                }
            });
            builder.environment().clear();
            builder.environment().putAll(safeEnvironment);
            process = builder.start();

            stdout = readAsync(process.getInputStream());
            stderr = readAsync(process.getErrorStream());
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            boolean timedOut = !finished;
            if (!finished) {
                terminate(process);
                process.waitFor(2, TimeUnit.SECONDS);
            }
            return new ProcessResult(
                    finished ? process.exitValue() : -1,
                    awaitOutput(stdout),
                    awaitOutput(stderr),
                    Duration.ofNanos(System.nanoTime() - started),
                    timedOut,
                    false);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start process: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            if (process != null) {
                terminate(process);
                try {
                    process.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // Preserve the original cancellation below.
                }
            }
            String out = awaitOutput(stdout);
            String err = awaitOutput(stderr);
            Thread.currentThread().interrupt();
            return new ProcessResult(-1, out, err,
                    Duration.ofNanos(System.nanoTime() - started), false, true);
        }
    }

    private boolean isSensitiveName(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.contains("API_KEY")
                || upper.contains("AUTH_TOKEN")
                || upper.equals("TOKEN")
                || upper.endsWith("_TOKEN")
                || upper.contains("PASSWORD")
                || upper.contains("SECRET")
                || upper.contains("CREDENTIAL")
                || upper.contains("PRIVATE_KEY")
                || upper.contains("COOKIE")
                || upper.startsWith("AWS_")
                || upper.startsWith("AZURE_")
                || upper.startsWith("GOOGLE_")
                || upper.startsWith("GCP_")
                || upper.startsWith("GITHUB_")
                || upper.startsWith("GITLAB_")
                || upper.startsWith("SSH_");
    }

    private void terminate(Process process) {
        process.toHandle().descendants().forEach(handle -> handle.destroyForcibly());
        process.destroyForcibly();
    }

    private String awaitOutput(CompletableFuture<String> output) {
        try {
            return output.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            output.cancel(true);
            return "...[process output unavailable: interrupted]...";
        } catch (ExecutionException | TimeoutException error) {
            output.cancel(true);
            return "...[process output unavailable: " + error.getClass().getSimpleName() + "]...";
        }
    }

    private CompletableFuture<String> readAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                ByteArrayOutputStream head = new ByteArrayOutputStream(Math.min(HEAD_CAPTURE_BYTES, 8192));
                byte[] tail = new byte[TAIL_CAPTURE_BYTES];
                int tailSize = 0;
                int tailPosition = 0;
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    total += read;
                    int headRemaining = HEAD_CAPTURE_BYTES - head.size();
                    int headBytes = Math.min(Math.max(headRemaining, 0), read);
                    if (headBytes > 0) {
                        head.write(buffer, 0, headBytes);
                    }
                    for (int index = headBytes; index < read; index++) {
                        if (tailSize < TAIL_CAPTURE_BYTES) {
                            tail[tailSize++] = buffer[index];
                        } else {
                            tail[tailPosition] = buffer[index];
                            tailPosition = (tailPosition + 1) % TAIL_CAPTURE_BYTES;
                        }
                    }
                }
                byte[] headBytes = head.toByteArray();
                byte[] tailBytes = new byte[tailSize];
                if (tailSize > 0) {
                    int start = tailSize == TAIL_CAPTURE_BYTES ? tailPosition : 0;
                    for (int index = 0; index < tailSize; index++) {
                        tailBytes[index] = tail[(start + index) % TAIL_CAPTURE_BYTES];
                    }
                }
                String headText = new String(headBytes, StandardCharsets.UTF_8);
                String tailText = new String(tailBytes, StandardCharsets.UTF_8);
                if (total > MAX_CAPTURE_BYTES) {
                    return headText + "\n...[process output truncated; head/tail retained]...\n" + tailText;
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream(headBytes.length + tailBytes.length);
                output.write(headBytes);
                output.write(tailBytes);
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
            boolean timedOut,
            boolean cancelled) {
        public ProcessResult(int exitCode,
                             String stdout,
                             String stderr,
                             Duration duration,
                             boolean timedOut) {
            this(exitCode, stdout, stderr, duration, timedOut, false);
        }
    }
}
