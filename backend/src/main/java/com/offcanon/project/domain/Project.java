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

    /** Owner used by pre-identity domain fixtures and legacy rows during migration. */
    public static final UUID LEGACY_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    public Project(UUID id,
                   String name,
                   Path canonicalPath,
                   List<String> verificationCommands,
                   Instant createdAt,
                   long version) {
        this(id, name, canonicalPath, verificationCommands, createdAt, version, LEGACY_OWNER_ID);
    }

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

    public static Project create(String name, Path canonicalPath, List<String> verificationCommands, Instant now) {
        return create(LEGACY_OWNER_ID, name, canonicalPath, verificationCommands, now);
    }

    public static Project create(UUID ownerId,
                                 String name,
                                 Path canonicalPath,
                                 List<String> verificationCommands,
                                 Instant now) {
        return new Project(UUID.randomUUID(), name.trim(), canonicalPath, verificationCommands, now, 0, ownerId);
    }
}
