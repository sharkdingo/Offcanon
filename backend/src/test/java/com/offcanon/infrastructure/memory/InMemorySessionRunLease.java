package com.offcanon.infrastructure.memory;

import com.offcanon.port.SessionRunLeasePort;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
public class InMemorySessionRunLease implements SessionRunLeasePort {
    private final ConcurrentHashMap<UUID, InMemoryLease> owners = new ConcurrentHashMap<>();

    @Override
    public Optional<Lease> tryAcquire(UUID sessionId, UUID experimentId) {
        InMemoryLease candidate = new InMemoryLease(sessionId, experimentId);
        return owners.putIfAbsent(sessionId, candidate) == null
                ? Optional.of(candidate)
                : Optional.empty();
    }

    @Override
    public void revoke(UUID sessionId, UUID experimentId) {
        owners.computeIfPresent(sessionId, (ignored, current) -> {
            if (!current.experimentId().equals(experimentId)) return current;
            current.invalidate();
            return null;
        });
    }

    private final class InMemoryLease implements Lease {
        private final UUID sessionId;
        private final UUID experimentId;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private InMemoryLease(UUID sessionId, UUID experimentId) {
            this.sessionId = java.util.Objects.requireNonNull(sessionId, "sessionId");
            this.experimentId = java.util.Objects.requireNonNull(experimentId, "experimentId");
        }

        @Override
        public UUID sessionId() {
            return sessionId;
        }

        @Override
        public UUID experimentId() {
            return experimentId;
        }

        @Override
        public void assertHeld() {
            if (!active.get() || owners.get(sessionId) != this) {
                throw new com.offcanon.shared.domain.DomainException("SESSION_RUN_LEASE_LOST",
                        "Session run lease was lost");
            }
        }

        @Override
        public void release() {
            if (active.getAndSet(false)) {
                owners.remove(sessionId, this);
            }
        }

        private void invalidate() {
            active.set(false);
        }
    }
}
