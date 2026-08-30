package com.offcanon.port;

import com.offcanon.memory.domain.TaskMemoryRevision;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskMemoryRepository {
    TaskMemoryRevision append(TaskMemoryRevision revision);
    Optional<TaskMemoryRevision> findById(UUID id);
    List<TaskMemoryRevision> findBySessionId(UUID sessionId);
    long nextSequence(UUID sessionId);
}
