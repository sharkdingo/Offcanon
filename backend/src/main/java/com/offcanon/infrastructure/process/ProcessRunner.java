package com.offcanon.infrastructure.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

public class ProcessRunner {
    private static final int MAX_CAPTURE_BYTES = 1_000_000;
    private static final int HEAD_CAPTURE_BYTES = MAX_CAPTURE_BYTES / 2;
    private static final int TAIL_CAPTURE_BYTES = MAX_CAPTURE_BYTES - HEAD_CAPTURE_BYTES;
    public static final int MAX_REQUESTED_ENVIRONMENT_ENTRIES = 16;
    public static final int MAX_REQUESTED_ENVIRONMENT_NAME_LENGTH = 64;
    public static final int MAX_REQUESTED_ENVIRONMENT_VALUE_LENGTH = 4_096;
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Set<String> INHERITED_ENVIRONMENT = Set.of(
            "APPDATA", "CARGO_HOME", "CI", "CLASSPATH", "COLORTERM", "COMSPEC",
            "DOTNET_ROOT", "GOPATH", "GOROOT", "GRADLE_HOME", "GRADLE_USER_HOME",
            "HOME", "HOMEDRIVE", "HOMEPATH", "JAVA_HOME", "LANG", "LANGUAGE",
            "LC_ALL", "LC_CTYPE", "LOCALAPPDATA", "M2_HOME", "MAVEN_HOME",
            "NODE_HOME", "NO_COLOR", "NPM_CONFIG_CACHE", "NUMBER_OF_PROCESSORS", "OS",
            "PATH", "PATHEXT", "PNPM_HOME", "PROCESSOR_ARCHITECTURE", "PROGRAMDATA",
            "RUSTUP_HOME", "SYSTEMROOT", "TEMP", "TERM", "TMP", "TMPDIR", "TZ",
            "USERPROFILE", "WINDIR");
    private static final Set<String> EXPLICIT_ENVIRONMENT = Set.of(
            "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_CEILING_DIRECTORIES", "GIT_DIR", "GIT_INDEX_FILE",
            "GIT_OBJECT_DIRECTORY", "GIT_WORK_TREE", "OFFCANON_EXPERIMENT_ID");
    private static final Set<String> PROTECTED_ENVIRONMENT = Set.of(
            "PATH", "PATHEXT", "COMSPEC", "SYSTEMROOT", "WINDIR", "USERPROFILE",
            "HOMEDRIVE", "HOMEPATH", "HOME", "TEMP", "TMP", "TMPDIR", "PWD", "OLDPWD",
            "CD", "PROMPT", "PSMODULEPATH", "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE",
            "JAVA_HOME", "MAVEN_HOME", "GRADLE_HOME", "NODE_HOME", "NPM_CONFIG_CACHE",
            "GOPATH", "GOROOT", "CARGO_HOME", "RUSTUP_HOME", "NODE_OPTIONS", "NODE_PATH",
            "PYTHONPATH", "PYTHONHOME", "PYTHONSTARTUP", "RUBYOPT", "PERL5OPT", "JAVA_TOOL_OPTIONS",
            "MAVEN_OPTS", "GRADLE_OPTS", "BASH_ENV", "ENV", "LD_PRELOAD", "LD_LIBRARY_PATH",
            "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH", "DYLD_FRAMEWORK_PATH", "COMPLUS_ReadyToRun",
            "COR_ENABLE_PROFILING", "CORECLR_ENABLE_PROFILING", "CORECLR_PROFILER", "RUSTC_WRAPPER");

    public ProcessResult run(List<String> command, Path cwd, Map<String, String> environment, Duration timeout) {
        long started = System.nanoTime();
        Process process = null;
        CompletableFuture<String> stdout = CompletableFuture.completedFuture("");
        CompletableFuture<String> stderr = CompletableFuture.completedFuture("");
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
            Map<String, String> safeEnvironment = sanitizedEnvironment(builder.environment(), environment);
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

    Map<String, String> sanitizedEnvironment(Map<String, String> inherited,
                                             Map<String, String> requested) {
        Map<String, String> safe = new LinkedHashMap<>();
        Map<String, String> inheritedValues = inherited == null ? Map.of() : inherited;
        Map<String, String> requestedValues = requested == null ? Map.of() : requested;
        inheritedValues.forEach((name, value) -> {
            if (name == null || value == null) return;
            String normalized = name.toUpperCase(Locale.ROOT);
            if (INHERITED_ENVIRONMENT.contains(normalized) && !isSensitiveName(name)) {
                safe.put(name, value);
            }
        });
        if (requestedValues.size() > MAX_REQUESTED_ENVIRONMENT_ENTRIES) {
            throw new IllegalArgumentException("Too many requested process environment variables (maximum "
                    + MAX_REQUESTED_ENVIRONMENT_ENTRIES + ")");
        }
        Set<String> normalizedRequested = new HashSet<>();
        requestedValues.forEach((name, value) -> {
            String normalized = name.toUpperCase(Locale.ROOT);
            if (!normalizedRequested.add(normalized)) {
                throw new IllegalArgumentException("Duplicate process environment variable: " + name);
            }
            if (isInternalEnvironment(normalized)) {
                validateEnvironmentEntry(name, value);
                safe.put(name, value);
                return;
            }
            validateRequestedEnvironmentEntry(name, value);
            if (PROTECTED_ENVIRONMENT.contains(normalized)
                    || normalized.startsWith("GIT_")
                    || normalized.startsWith("OFFCANON_")) {
                throw new IllegalArgumentException("Process environment variable is not allowed: " + name);
            }
            safe.put(name, value);
        });
        return safe;
    }

    /**
     * Validates a user-requested environment entry before it reaches a
     * ProcessBuilder. Internal control variables are intentionally excluded;
     * callers should only pass those from trusted infrastructure code.
     */
    public static void validateRequestedEnvironmentEntry(String name, String value) {
        validateEnvironmentEntry(name, value);
        String upper = name.toUpperCase(Locale.ROOT);
        if (PROTECTED_ENVIRONMENT.contains(upper)
                || upper.startsWith("GIT_")
                || upper.startsWith("OFFCANON_")) {
            throw new IllegalArgumentException("Process environment variable is not allowed: " + name);
        }
    }

    private static void validateEnvironmentEntry(String name, String value) {
        if (name == null || value == null || !ENVIRONMENT_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Process environment variable name/value is invalid");
        }
        if (name.length() > MAX_REQUESTED_ENVIRONMENT_NAME_LENGTH) {
            throw new IllegalArgumentException("Process environment variable name is too long: " + name);
        }
        if (value.length() > MAX_REQUESTED_ENVIRONMENT_VALUE_LENGTH) {
            throw new IllegalArgumentException("Process environment variable value is too long: " + name);
        }
        if (value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Process environment variable contains a control character: " + name);
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (isSensitiveNameStatic(upper)) {
            throw new IllegalArgumentException("Process environment variable is not allowed: " + name);
        }
    }

    private static boolean isInternalEnvironment(String normalized) {
        return EXPLICIT_ENVIRONMENT.contains(normalized);
    }

    private boolean isSensitiveName(String name) {
        return isSensitiveNameStatic(name.toUpperCase(Locale.ROOT));
    }

    private static boolean isSensitiveNameStatic(String upper) {
        Objects.requireNonNull(upper, "upper");
        return upper.contains("API_KEY")
                || upper.contains("AUTH_TOKEN")
                || upper.equals("TOKEN")
                || upper.endsWith("_TOKEN")
                || upper.contains("PASSWORD")
                || upper.contains("SECRET")
                || upper.contains("CREDENTIAL")
                || upper.contains("PRIVATE_KEY")
                || upper.contains("ACCESS_KEY")
                || upper.contains("COOKIE")
                || upper.equals("DATABASE_URL")
                || upper.endsWith("_DATABASE_URL")
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
