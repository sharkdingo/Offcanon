package com.pico.infrastructure.memory;

import com.pico.port.SnapshotRepository;
import com.pico.workspace.domain.Snapshot;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("!mysql")
public class InMemorySnapshotRepository implements SnapshotRepository {
    private final ConcurrentHashMap<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public Snapshot save(Snapshot snapshot) {
        snapshots.put(snapshot.id(), snapshot);
        return snapshot;
    }

    @Override
    public Optional<Snapshot> findById(UUID id) {
        return Optional.ofNullable(snapshots.get(id));
    }
}
