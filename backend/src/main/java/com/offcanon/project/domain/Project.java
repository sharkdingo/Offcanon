package com.offcanon.project.domain;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Project(
        UUID id,
        String name,
        Path canonicalPath,
        List<String> verificationCommands,
        Instant createdAt,
        long version,
        UUID ownerId) {

    public Project {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(canonicalPath, "canonicalPath");
        Objects.requireNonNull(verificationCommands, "verificationCommands");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(ownerId, "ownerId");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Project name must not be blank");
        }
        canonicalPath = canonicalPath.toAbsolutePath().normalize();
        verificationCommands = List.copyOf(verificationCommands);
    }

    public static Project create(UUID ownerId,
                                 String name,
                                 Path canonicalPath,
                                 List<String> verificationCommands,
                                 Instant now) {
        return new Project(UUID.randomUUID(), name.trim(), canonicalPath, verificationCommands, now, 0, ownerId);
    }

    /**
     * Update only user-facing project metadata. The canonical path and owner
     * remain immutable because experiments and snapshots are bound to them.
     */
    public Project updated(String name, List<String> verificationCommands) {
        return new Project(id, name.trim(), canonicalPath, verificationCommands,
                createdAt, version + 1, ownerId);
    }
}
