package com.offcanon.agent.domain;

import com.offcanon.shared.domain.DomainException;

import java.time.Duration;
import java.util.Optional;

public final class ModelTransientException extends DomainException {
    private final Duration retryAfter;

    public ModelTransientException(String message) {
        this(message, null);
    }

    public ModelTransientException(String message, Duration retryAfter) {
        super("MODEL_TRANSIENT_FAILURE", message);
        this.retryAfter = retryAfter == null || retryAfter.isNegative() ? null : retryAfter;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
