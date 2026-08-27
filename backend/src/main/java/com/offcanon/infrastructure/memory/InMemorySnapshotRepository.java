package com.offcanon.infrastructure.memory;

import com.offcanon.port.SnapshotRepository;
import com.offcanon.workspace.domain.Snapshot;
import com.offcanon.shared.domain.DomainException;
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
        Snapshot existing = snapshots.putIfAbsent(snapshot.id(), snapshot);
        if (existing != null && !existing.equals(snapshot)) {
            throw new DomainException("SNAPSHOT_IDENTITY_CONFLICT",
                    "Snapshot identity is already bound to different content: " + snapshot.id());
        }
        return existing == null ? snapshot : existing;
    }

    @Override
    public Optional<Snapshot> findById(UUID id) {
        return Optional.ofNullable(snapshots.get(id));
    }
}
