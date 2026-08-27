package com.offcanon.infrastructure.memory;

import com.offcanon.shared.domain.DomainException;
import com.offcanon.workspace.domain.Snapshot;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemorySnapshotRepositoryTest {
    @Test
    void snapshotIdentityIsInsertOnlyButExactSavesAreIdempotent() {
        InMemorySnapshotRepository repository = new InMemorySnapshotRepository();
        UUID id = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        Instant captured = Instant.parse("2026-08-27T10:00:00Z");
        Snapshot original = new Snapshot(id, projectId, "tree-a", Path.of("C:/offcanon/a"), captured,
                List.of("service.txt"), List.of());

        assertSame(original, repository.save(original));
        assertSame(original, repository.save(original));
        Snapshot conflicting = new Snapshot(id, projectId, "tree-b", Path.of("C:/offcanon/b"), captured,
                List.of("other.txt"), List.of());

        DomainException error = assertThrows(DomainException.class, () -> repository.save(conflicting));
        assertEquals("SNAPSHOT_IDENTITY_CONFLICT", error.code());
        assertEquals(original, repository.findById(id).orElseThrow());
    }
}
