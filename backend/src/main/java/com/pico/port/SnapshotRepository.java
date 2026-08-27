package com.pico.port;

import com.pico.workspace.domain.Snapshot;

import java.util.Optional;
import java.util.UUID;

public interface SnapshotRepository {
    Snapshot save(Snapshot snapshot);
    Optional<Snapshot> findById(UUID id);
}
