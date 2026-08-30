package com.offcanon.shared.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One conservative predicate for files that must never enter agent context
 * or an experiment promotion. The check is relative-path based so it can be
 * reused by filesystem traversal, snapshot, diff and promotion adapters.
 */
public final class SensitivePathPolicy {
    private static final java.util.Set<String> SAFE_ENV_TEMPLATES = java.util.Set.of(
            ".env.example", ".env.sample", ".env.template");
    private static final Pattern SENSITIVE_REFERENCE = Pattern.compile(
            "(?i)(^|[\\s\\\"'=:/\\\\])\\.env(?:\\.[a-z0-9_-]+)?(?=$|[\\s\\\"'&|<>:/\\\\])");

    private SensitivePathPolicy() {
    }

    public static boolean isSensitiveRelativePath(String relative) {
        if (relative == null || relative.isBlank()) return false;
        for (String part : relative.replace('\\', '/').split("/")) {
            String normalized = part.toLowerCase(Locale.ROOT);
            if (normalized.equals(".env")) return true;
            if (normalized.startsWith(".env.") && !SAFE_ENV_TEMPLATES.contains(normalized)) return true;
        }
        return false;
    }

    /** Detects an obvious shell reference without pretending to parse a shell. */
    public static boolean containsSensitivePathReference(String command) {
        if (command == null || command.isBlank()) return false;
        var matcher = SENSITIVE_REFERENCE.matcher(command);
        while (matcher.find()) {
            String match = matcher.group();
            int start = match.toLowerCase(Locale.ROOT).lastIndexOf(".env");
            String fileName = start < 0 ? match : match.substring(start);
            if (isSensitiveRelativePath(fileName)) return true;
        }
        return false;
    }
}
