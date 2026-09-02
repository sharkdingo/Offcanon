package com.offcanon.memory.application;

import com.offcanon.memory.domain.TaskMemoryProjection;
import com.offcanon.memory.domain.TaskMemoryRevision;
import com.offcanon.memory.domain.TaskMemoryKind;
import com.offcanon.memory.domain.TaskMemoryStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Pure projection: the same ledger and fingerprint always produce the same view. */
@Component
public class TaskMemoryProjector {
    private static final Comparator<TaskMemoryRevision> ORDER = Comparator
            .comparingLong(TaskMemoryRevision::sequence)
            .thenComparing(revision -> revision.id().toString());

    public TaskMemoryProjection project(UUID projectId,
                                        UUID sessionId,
                                        String currentFingerprint,
                                        List<TaskMemoryRevision> ledger) {
        return project(projectId, sessionId, currentFingerprint, ledger, Set.of());
    }

    /**
     * Projects the ledger while allowing the application boundary to mark
     * trusted facts whose source experiment is no longer an accepted result.
     * The ledger remains immutable; those facts are simply presented as
     * historical until their source is verified again.
     */
    public TaskMemoryProjection project(UUID projectId,
                                        UUID sessionId,
                                        String currentFingerprint,
                                        List<TaskMemoryRevision> ledger,
                                        Set<UUID> invalidatedVerifiedExperiments) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(currentFingerprint, "currentFingerprint");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(invalidatedVerifiedExperiments, "invalidatedVerifiedExperiments");
        if (currentFingerprint.isBlank()) throw new IllegalArgumentException("Current fingerprint must not be blank");
        Set<UUID> invalidated = Set.copyOf(invalidatedVerifiedExperiments);

        List<TaskMemoryRevision> ordered = ledger.stream().sorted(ORDER).toList();
        validateScope(projectId, sessionId, ordered);

        Map<UUID, TaskMemoryRevision> visible = new LinkedHashMap<>();
        for (TaskMemoryRevision revision : ordered) {
            if (revision.status().isVisible()) visible.put(revision.id(), revision);
        }
        validateReferences(ordered);

        // A stale proposal is historical information, not an authority to hide
        // an accepted revision written against the current Snapshot.
        Set<UUID> superseded = new HashSet<>();
        visible.values().stream()
                .filter(revision -> revision.status() == TaskMemoryStatus.ACCEPTED
                        && revision.appliesTo(currentFingerprint))
                .forEach(revision -> superseded.addAll(revision.supersedesIds()));
        List<TaskMemoryRevision> leaves = visible.values().stream()
                .filter(revision -> !superseded.contains(revision.id()))
                .toList();
        Set<UUID> branchedLeaves = branchedLeaves(leaves, ordered, currentFingerprint);

        List<TaskMemoryProjection.ProjectedMemory> current = new ArrayList<>();
        List<TaskMemoryProjection.ProjectedMemory> stale = new ArrayList<>();
        List<TaskMemoryProjection.ProjectedMemory> proposed = new ArrayList<>();
        List<TaskMemoryProjection.ProjectedMemory> conflicted = new ArrayList<>();
        for (TaskMemoryRevision revision : leaves) {
            boolean sourceAccepted = revision.kind() != TaskMemoryKind.VERIFIED_FACT
                    || !invalidated.contains(revision.sourceExperimentId());
            TaskMemoryProjection.Freshness freshness = sourceAccepted && revision.appliesTo(currentFingerprint)
                    && revision.status() != TaskMemoryStatus.STALE
                    ? TaskMemoryProjection.Freshness.CURRENT
                    : TaskMemoryProjection.Freshness.STALE;
            TaskMemoryProjection.ProjectedMemory projected =
                    new TaskMemoryProjection.ProjectedMemory(revision, freshness);
            if (revision.status() == TaskMemoryStatus.CONFLICTED || branchedLeaves.contains(revision.id())) {
                conflicted.add(projected);
            } else if (revision.status() == TaskMemoryStatus.PROPOSED) {
                proposed.add(projected);
            } else if (freshness == TaskMemoryProjection.Freshness.STALE) {
                stale.add(projected);
            } else {
                current.add(projected);
            }
        }
        return new TaskMemoryProjection(projectId, sessionId, currentFingerprint,
                current, stale, proposed, conflicted);
    }

    private void validateScope(UUID projectId, UUID sessionId, List<TaskMemoryRevision> ledger) {
        for (TaskMemoryRevision revision : ledger) {
            if (!revision.projectId().equals(projectId) || !revision.sessionId().equals(sessionId)) {
                throw new IllegalArgumentException("Task memory projection crossed a project or session boundary");
            }
        }
    }

    private void validateReferences(List<TaskMemoryRevision> ledger) {
        Set<UUID> allIds = ledger.stream().map(TaskMemoryRevision::id).collect(java.util.stream.Collectors.toSet());
        for (TaskMemoryRevision revision : ledger) {
            for (UUID superseded : revision.supersedesIds()) {
                if (!allIds.contains(superseded)) {
                    throw new IllegalArgumentException("Task memory supersedes an unknown revision: " + superseded);
                }
            }
        }
    }

    private Set<UUID> branchedLeaves(List<TaskMemoryRevision> leaves,
                                     List<TaskMemoryRevision> ledger,
                                     String currentFingerprint) {
        Map<UUID, UUID> parent = new HashMap<>();
        ledger.forEach(revision -> parent.put(revision.id(), revision.id()));
        for (TaskMemoryRevision revision : ledger) {
            if (revision.status() != TaskMemoryStatus.ACCEPTED
                    || !revision.appliesTo(currentFingerprint)) continue;
            for (UUID superseded : revision.supersedesIds()) union(parent, revision.id(), superseded);
        }
        Map<UUID, List<UUID>> leavesByComponent = new HashMap<>();
        for (TaskMemoryRevision leaf : leaves) {
            leavesByComponent.computeIfAbsent(find(parent, leaf.id()), ignored -> new ArrayList<>()).add(leaf.id());
        }
        Set<UUID> result = new HashSet<>();
        leavesByComponent.values().stream().filter(group -> group.size() > 1).forEach(result::addAll);
        return result;
    }

    private void union(Map<UUID, UUID> parent, UUID left, UUID right) {
        UUID leftRoot = find(parent, left);
        UUID rightRoot = find(parent, right);
        if (!leftRoot.equals(rightRoot)) parent.put(leftRoot, rightRoot);
    }

    private UUID find(Map<UUID, UUID> parent, UUID id) {
        UUID current = parent.get(id);
        if (current == null) throw new IllegalArgumentException("Unknown task memory lineage revision: " + id);
        while (!current.equals(parent.get(current))) current = parent.get(current);
        UUID root = current;
        current = id;
        while (!current.equals(root)) {
            UUID next = parent.get(current);
            parent.put(current, root);
            current = next;
        }
        return root;
    }
}
