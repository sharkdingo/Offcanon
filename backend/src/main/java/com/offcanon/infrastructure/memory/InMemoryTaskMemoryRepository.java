package com.offcanon.infrastructure.memory;

import com.offcanon.memory.domain.TaskMemoryRevision;
import com.offcanon.port.TaskMemoryRepository;
import com.offcanon.shared.domain.DomainException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("!mysql")
public class InMemoryTaskMemoryRepository implements TaskMemoryRepository {
    private final ConcurrentHashMap<UUID, TaskMemoryRevision> revisions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> sessionLocks = new ConcurrentHashMap<>();

    @Override
    public TaskMemoryRevision append(TaskMemoryRevision revision) {
        Object lock = sessionLocks.computeIfAbsent(revision.sessionId(), ignored -> new Object());
        synchronized (lock) {
            TaskMemoryRevision existing = revisions.get(revision.id());
            if (existing != null) {
                if (existing.equals(revision)) return existing;
                throw identityConflict(revision.id());
            }
            boolean occupied = revisions.values().stream().anyMatch(item -> item.sessionId().equals(revision.sessionId())
                    && item.sequence() == revision.sequence());
            if (occupied) throw sequenceConflict(revision.sessionId(), revision.sequence());
            revisions.put(revision.id(), revision);
            return revision;
        }
    }

    @Override
    public Optional<TaskMemoryRevision> findById(UUID id) {
        return Optional.ofNullable(revisions.get(id));
    }

    @Override
    public List<TaskMemoryRevision> findBySessionId(UUID sessionId) {
        return revisions.values().stream()
                .filter(revision -> revision.sessionId().equals(sessionId))
                .sorted(Comparator.comparingLong(TaskMemoryRevision::sequence)
                        .thenComparing(revision -> revision.id().toString()))
                .toList();
    }

    @Override
    public long nextSequence(UUID sessionId) {
        return revisions.values().stream()
                .filter(revision -> revision.sessionId().equals(sessionId))
                .mapToLong(TaskMemoryRevision::sequence)
                .max().orElse(0) + 1;
    }

    private DomainException identityConflict(UUID id) {
        return new DomainException("TASK_MEMORY_IDENTITY_CONFLICT",
                "Task memory identity is already bound to different content: " + id);
    }

    private DomainException sequenceConflict(UUID sessionId, long sequence) {
        return new DomainException("TASK_MEMORY_SEQUENCE_CONFLICT",
                "Task memory sequence changed concurrently for session " + sessionId + ": " + sequence);
    }
}
