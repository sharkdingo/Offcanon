package com.offcanon.agent.domain;

import com.offcanon.identity.domain.UserSettings;

/** Per-run controls resolved from the authenticated user's settings. */
public record AgentRunSettings(int maxSteps,
                               long runTimeoutSeconds,
                               int contextLimitChars,
                               String modelEndpoint,
                               String modelName,
                               String modelApiKey) {
    public AgentRunSettings {
        if (maxSteps < 1 || maxSteps > 100) throw new IllegalArgumentException("Agent max steps must be between 1 and 100");
        if (runTimeoutSeconds < 10 || runTimeoutSeconds > 86_400) throw new IllegalArgumentException("Agent run timeout must be between 10 and 86400 seconds");
        if (contextLimitChars < 8_000 || contextLimitChars > 1_000_000) throw new IllegalArgumentException("Agent context limit must be between 8000 and 1000000 characters");
        modelEndpoint = modelEndpoint == null ? "" : modelEndpoint.trim();
        modelName = modelName == null ? "" : modelName.trim();
        modelApiKey = modelApiKey == null ? "" : modelApiKey.trim();
        if (modelApiKey.length() > 4096) throw new IllegalArgumentException("Model API key is too long");
    }

    public static AgentRunSettings from(UserSettings settings) {
        return new AgentRunSettings(settings.agentMaxSteps(), settings.agentRunTimeoutSeconds(),
                settings.contextLimitChars(), settings.modelEndpoint(), settings.modelName(), settings.modelApiKey());
    }
}
