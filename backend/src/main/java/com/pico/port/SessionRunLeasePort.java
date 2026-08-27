package com.pico.port;

import java.util.UUID;

public interface SessionRunLeasePort {
    boolean tryAcquire(UUID sessionId, UUID experimentId);
    void release(UUID sessionId, UUID experimentId);
}
