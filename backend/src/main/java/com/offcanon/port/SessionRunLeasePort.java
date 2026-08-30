package com.offcanon.port;

import java.util.Optional;
import java.util.UUID;

public interface SessionRunLeasePort {
    /**
     * Acquires exclusive execution ownership for a session. The returned handle
     * is the ownership capability: a stale holder cannot assert or release a
     * lease acquired later for the same experiment.
     */
    Optional<Lease> tryAcquire(UUID sessionId, UUID experimentId);

    /**
     * Administratively invalidates the current lease only when it belongs to
     * the specified experiment. This is used by durable cancellation, not by a
     * holder's normal cleanup path.
     */
    void revoke(UUID sessionId, UUID experimentId);

    interface Lease extends AutoCloseable {
        UUID sessionId();
        UUID experimentId();

        /** Fails closed when ownership expired, was revoked, or was replaced. */
        void assertHeld();

        /** Releases this exact acquisition without affecting a later holder. */
        void release();

        @Override
        default void close() {
            release();
        }
    }
}
