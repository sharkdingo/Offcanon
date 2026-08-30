package com.offcanon.infrastructure.memory;

import com.offcanon.memory.domain.TaskMemoryKind;
import com.offcanon.memory.domain.TaskMemoryOrigin;
import com.offcanon.memory.domain.TaskMemoryRevision;
import com.offcanon.memory.domain.TaskMemoryStatus;
import com.offcanon.memory.domain.TaskMemoryTrust;
import com.offcanon.shared.domain.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryTaskMemoryRepositoryTest {
    @Test
    void appendIsIdempotentButSequenceIsUniqueWithinSession() {
        InMemoryTaskMemoryRepository repository = new InMemoryTaskMemoryRepository();
        UUID project = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        TaskMemoryRevision first = revision(UUID.randomUUID(), project, session, 1, "first");

        assertEquals(first, repository.append(first));
        assertEquals(first, repository.append(first));
        assertEquals(2, repository.nextSequence(session));

        DomainException conflict = assertThrows(DomainException.class,
                () -> repository.append(revision(UUID.randomUUID(), project, session, 1, "other")));
        assertEquals("TASK_MEMORY_SEQUENCE_CONFLICT", conflict.code());
    }

    private TaskMemoryRevision revision(UUID id, UUID project, UUID session, long sequence, String content) {
        return new TaskMemoryRevision(id, project, session, UUID.randomUUID(), UUID.randomUUID(), "fingerprint",
                TaskMemoryKind.CONSTRAINT, content, List.of(), TaskMemoryOrigin.USER_AUTHORED,
                TaskMemoryTrust.USER_CONFIRMED, TaskMemoryStatus.ACCEPTED, List.of(), Instant.EPOCH, sequence);
    }
}
