package com.offcanon.memory.application;

import com.offcanon.memory.domain.TaskMemoryKind;
import com.offcanon.memory.domain.TaskMemoryOrigin;
import com.offcanon.memory.domain.TaskMemoryProjection;
import com.offcanon.memory.domain.TaskMemoryRevision;
import com.offcanon.memory.domain.TaskMemoryStatus;
import com.offcanon.memory.domain.TaskMemoryTrust;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskMemoryProjectorTest {
    private final UUID project = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();
    private final TaskMemoryProjector projector = new TaskMemoryProjector();

    @Test
    void separatesCurrentStaleProposedAndExplicitlyConflictedMemory() {
        TaskMemoryRevision current = revision(1, "current", "fp-current", TaskMemoryStatus.ACCEPTED, List.of());
        TaskMemoryRevision stale = revision(2, "stale", "fp-old", TaskMemoryStatus.ACCEPTED, List.of());
        TaskMemoryRevision proposed = agentRevision(3, "proposal", "fp-current", List.of());
        TaskMemoryRevision conflicted = revision(4, "conflict", "fp-current", TaskMemoryStatus.CONFLICTED, List.of());

        TaskMemoryProjection result = projector.project(project, session, "fp-current",
                List.of(conflicted, proposed, stale, current));

        assertEquals(List.of(current), result.current().stream().map(TaskMemoryProjection.ProjectedMemory::revision).toList());
        assertEquals(List.of(stale), result.stale().stream().map(TaskMemoryProjection.ProjectedMemory::revision).toList());
        assertEquals(List.of(proposed), result.proposed().stream().map(TaskMemoryProjection.ProjectedMemory::revision).toList());
        assertEquals(List.of(conflicted), result.conflicted().stream().map(TaskMemoryProjection.ProjectedMemory::revision).toList());
    }

    @Test
    void detectsBranchesAndAllowsAnExplicitMergeRevisionToResolveThem() {
        TaskMemoryRevision root = revision(1, "root", "fp", TaskMemoryStatus.ACCEPTED, List.of());
        TaskMemoryRevision left = revision(2, "left", "fp", TaskMemoryStatus.ACCEPTED, List.of(root.id()));
        TaskMemoryRevision right = revision(3, "right", "fp", TaskMemoryStatus.ACCEPTED, List.of(root.id()));

        TaskMemoryProjection branched = projector.project(project, session, "fp", List.of(root, left, right));
        assertEquals(List.of(left, right), branched.conflicted().stream()
                .map(TaskMemoryProjection.ProjectedMemory::revision).toList());

        TaskMemoryRevision merged = revision(4, "merged", "fp", TaskMemoryStatus.ACCEPTED,
                List.of(left.id(), right.id()));
        TaskMemoryProjection resolved = projector.project(project, session, "fp", List.of(root, left, right, merged));
        assertEquals(List.of(merged), resolved.current().stream()
                .map(TaskMemoryProjection.ProjectedMemory::revision).toList());
        assertEquals(List.of(), resolved.conflicted());
    }

    @Test
    void staleAcceptedRevisionCannotSupersedeCurrentMemory() {
        TaskMemoryRevision current = revision(1, "current", "fp-current", TaskMemoryStatus.ACCEPTED, List.of());
        TaskMemoryRevision stale = revision(2, "old replacement", "fp-old", TaskMemoryStatus.ACCEPTED,
                List.of(current.id()));

        TaskMemoryProjection result = projector.project(project, session, "fp-current", List.of(current, stale));

        assertEquals(List.of(current), result.current().stream()
                .map(TaskMemoryProjection.ProjectedMemory::revision).toList());
        assertEquals(List.of(stale), result.stale().stream()
                .map(TaskMemoryProjection.ProjectedMemory::revision).toList());
    }

    private TaskMemoryRevision revision(long sequence,
                                        String content,
                                        String fingerprint,
                                        TaskMemoryStatus status,
                                        List<UUID> supersedes) {
        return new TaskMemoryRevision(UUID.randomUUID(), project, session, UUID.randomUUID(), UUID.randomUUID(),
                fingerprint, TaskMemoryKind.DECISION, content, List.of(), TaskMemoryOrigin.USER_AUTHORED,
                TaskMemoryTrust.USER_CONFIRMED, status, supersedes, Instant.EPOCH.plusSeconds(sequence), sequence);
    }

    private TaskMemoryRevision agentRevision(long sequence,
                                             String content,
                                             String fingerprint,
                                             List<UUID> supersedes) {
        return new TaskMemoryRevision(UUID.randomUUID(), project, session, UUID.randomUUID(), UUID.randomUUID(),
                fingerprint, TaskMemoryKind.DECISION, content, List.of(), TaskMemoryOrigin.AGENT_REPORTED,
                TaskMemoryTrust.AGENT_REPORTED, TaskMemoryStatus.PROPOSED, supersedes,
                Instant.EPOCH.plusSeconds(sequence), sequence);
    }
}
