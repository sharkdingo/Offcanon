package com.offcanon.infrastructure.sqlite;

import com.offcanon.port.SessionRunLeasePort;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-process execution lease used by the desktop deployment. */
@Component
public final class LocalSessionRunLease implements SessionRunLeasePort {
    private final ConcurrentHashMap<UUID, LocalLease> owners = new ConcurrentHashMap<>();

    @Override
    public Optional<Lease> tryAcquire(UUID sessionId, UUID experimentId) {
        LocalLease candidate = new LocalLease(sessionId, experimentId);
        return owners.putIfAbsent(sessionId, candidate) == null ? Optional.of(candidate) : Optional.empty();
    }

    @Override
    public void revoke(UUID sessionId, UUID experimentId) {
        owners.computeIfPresent(sessionId, (ignored, current) -> {
            if (!current.experimentId.equals(experimentId)) return current;
            current.active.set(false);
            return null;
        });
    }

    private final class LocalLease implements Lease {
        private final UUID sessionId;
        private final UUID experimentId;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private LocalLease(UUID sessionId, UUID experimentId) {
            this.sessionId = java.util.Objects.requireNonNull(sessionId, "sessionId");
            this.experimentId = java.util.Objects.requireNonNull(experimentId, "experimentId");
        }

        @Override public UUID sessionId() { return sessionId; }
        @Override public UUID experimentId() { return experimentId; }

        @Override
        public void assertHeld() {
            if (!active.get() || owners.get(sessionId) != this) {
                throw new com.offcanon.shared.domain.DomainException("SESSION_RUN_LEASE_LOST", "Session run lease was lost");
            }
        }

        @Override
        public void release() {
            if (active.getAndSet(false)) owners.remove(sessionId, this);
        }
    }
}
