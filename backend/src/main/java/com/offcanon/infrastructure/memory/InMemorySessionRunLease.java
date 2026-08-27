package com.offcanon.infrastructure.memory;

import com.offcanon.port.SessionRunLeasePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("!redis")
public class InMemorySessionRunLease implements SessionRunLeasePort {
    private final ConcurrentHashMap<UUID, UUID> owners = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(UUID sessionId, UUID experimentId) {
        return owners.putIfAbsent(sessionId, experimentId) == null;
    }

    @Override
    public void release(UUID sessionId, UUID experimentId) {
        owners.remove(sessionId, experimentId);
    }
}
