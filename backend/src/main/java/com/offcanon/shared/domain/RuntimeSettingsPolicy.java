package com.offcanon.shared.domain;

/**
 * Deployment-owned defaults and ceilings for user-selectable Agent run
 * settings.  A user may choose a value below the ceiling, but cannot expand
 * the worker's resource budget through the Settings screen.
 */
public record RuntimeSettingsPolicy(
        int defaultMaxSteps,
        long defaultRunTimeoutSeconds,
        int defaultContextLimitChars,
        int maxStepsCeiling,
        long runTimeoutSecondsCeiling,
        int contextLimitCharsCeiling) {

    public static final int MIN_MAX_STEPS = 1;
    public static final long MIN_RUN_TIMEOUT_SECONDS = 10;
    public static final int MIN_CONTEXT_LIMIT_CHARS = 8_000;
    public static final int ABSOLUTE_MAX_STEPS = 100;
    public static final long ABSOLUTE_MAX_RUN_TIMEOUT_SECONDS = 86_400;
    public static final int ABSOLUTE_MAX_CONTEXT_LIMIT_CHARS = 1_000_000;

    public RuntimeSettingsPolicy {
        if (maxStepsCeiling < MIN_MAX_STEPS || maxStepsCeiling > ABSOLUTE_MAX_STEPS) {
            throw new IllegalArgumentException("Max steps ceiling must be between 1 and 100");
        }
        if (runTimeoutSecondsCeiling < MIN_RUN_TIMEOUT_SECONDS
                || runTimeoutSecondsCeiling > ABSOLUTE_MAX_RUN_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("Run timeout ceiling must be between 10 and 86400 seconds");
        }
        if (contextLimitCharsCeiling < MIN_CONTEXT_LIMIT_CHARS
                || contextLimitCharsCeiling > ABSOLUTE_MAX_CONTEXT_LIMIT_CHARS) {
            throw new IllegalArgumentException("Context limit ceiling must be between 8000 and 1000000 characters");
        }
        if (defaultMaxSteps < MIN_MAX_STEPS || defaultMaxSteps > maxStepsCeiling) {
            throw new IllegalArgumentException("Default max steps must not exceed the deployment ceiling");
        }
        if (defaultRunTimeoutSeconds < MIN_RUN_TIMEOUT_SECONDS
                || defaultRunTimeoutSeconds > runTimeoutSecondsCeiling) {
            throw new IllegalArgumentException("Default run timeout must not exceed the deployment ceiling");
        }
        if (defaultContextLimitChars < MIN_CONTEXT_LIMIT_CHARS
                || defaultContextLimitChars > contextLimitCharsCeiling) {
            throw new IllegalArgumentException("Default context limit must not exceed the deployment ceiling");
        }
    }

    public static RuntimeSettingsPolicy defaults() {
        return new RuntimeSettingsPolicy(20, 600, 80_000,
                ABSOLUTE_MAX_STEPS, ABSOLUTE_MAX_RUN_TIMEOUT_SECONDS,
                ABSOLUTE_MAX_CONTEXT_LIMIT_CHARS);
    }

    public void validate(int maxSteps, long runTimeoutSeconds, int contextLimitChars) {
        if (maxSteps < MIN_MAX_STEPS || maxSteps > maxStepsCeiling) {
            throw new DomainException("AGENT_MAX_STEPS_OUT_OF_POLICY",
                    "Max steps must be between 1 and the deployment ceiling of " + maxStepsCeiling);
        }
        if (runTimeoutSeconds < MIN_RUN_TIMEOUT_SECONDS || runTimeoutSeconds > runTimeoutSecondsCeiling) {
            throw new DomainException("AGENT_TIMEOUT_OUT_OF_POLICY",
                    "Run timeout must be between 10 and the deployment ceiling of "
                            + runTimeoutSecondsCeiling + " seconds");
        }
        if (contextLimitChars < MIN_CONTEXT_LIMIT_CHARS || contextLimitChars > contextLimitCharsCeiling) {
            throw new DomainException("AGENT_CONTEXT_OUT_OF_POLICY",
                    "Context limit must be between 8000 and the deployment ceiling of "
                            + contextLimitCharsCeiling + " characters");
        }
    }
}
