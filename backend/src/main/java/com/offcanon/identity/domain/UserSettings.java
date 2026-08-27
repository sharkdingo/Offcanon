package com.offcanon.identity.domain;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** User-owned preferences and model references. Secrets are intentionally absent. */
public record UserSettings(UUID userId,
                           String theme,
                           String locale,
                           String modelEndpoint,
                           String modelName,
                           int agentMaxSteps,
                           long agentRunTimeoutSeconds,
                           int contextLimitChars,
                           Instant updatedAt,
                           long version) {
    public UserSettings {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(theme, "theme");
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(modelEndpoint, "modelEndpoint");
        Objects.requireNonNull(modelName, "modelName");
        Objects.requireNonNull(updatedAt, "updatedAt");
        theme = theme.trim().toLowerCase(java.util.Locale.ROOT);
        locale = locale.trim();
        modelEndpoint = modelEndpoint.trim();
        modelName = modelName.trim();
        if (!theme.equals("system") && !theme.equals("light") && !theme.equals("dark")) {
            throw new IllegalArgumentException("Theme must be system, light or dark");
        }
        if (!locale.matches("[A-Za-z]{2,8}(?:[-_][A-Za-z0-9]{2,8})?")) {
            throw new IllegalArgumentException("Locale must be a language tag such as zh-CN or en-US");
        }
        if (modelEndpoint.length() > 2_048) throw new IllegalArgumentException("Model endpoint is too long");
        if (!modelEndpoint.isBlank()) {
            URI endpoint;
            try {
                endpoint = URI.create(modelEndpoint);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Model endpoint must be a valid HTTP(S) URL", error);
            }
            if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                    || endpoint.getHost() == null || endpoint.getUserInfo() != null) {
                throw new IllegalArgumentException("Model endpoint must be an HTTP(S) URL without embedded credentials");
            }
        }
        if (modelName.length() > 200) throw new IllegalArgumentException("Model name is too long");
        if (agentMaxSteps < 1 || agentMaxSteps > 100) throw new IllegalArgumentException("Agent max steps must be between 1 and 100");
        if (agentRunTimeoutSeconds < 10 || agentRunTimeoutSeconds > 86_400) {
            throw new IllegalArgumentException("Agent run timeout must be between 10 and 86400 seconds");
        }
        if (contextLimitChars < 8_000 || contextLimitChars > 1_000_000) {
            throw new IllegalArgumentException("Agent context limit must be between 8000 and 1000000 characters");
        }
        if (version < 0) throw new IllegalArgumentException("Settings version must not be negative");
    }

    public static UserSettings defaults(UUID userId, Instant now) {
        return new UserSettings(userId, "system", "zh-CN", "", "", 20, 600, 80_000, now, 0);
    }

    public UserSettings updated(String theme,
                                String locale,
                                String modelEndpoint,
                                String modelName,
                                int agentMaxSteps,
                                long agentRunTimeoutSeconds,
                                int contextLimitChars,
                                Instant now) {
        return new UserSettings(userId, theme, locale, modelEndpoint, modelName,
                agentMaxSteps, agentRunTimeoutSeconds, contextLimitChars, now, version + 1);
    }
}
