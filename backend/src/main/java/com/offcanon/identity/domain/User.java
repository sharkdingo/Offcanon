package com.offcanon.identity.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** A deliberately small local account; this is not an enterprise identity model. */
public record User(UUID id,
                   String username,
                   String passwordHash,
                   Instant createdAt,
                   long version) {
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,63}");

    public User {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(passwordHash, "passwordHash");
        Objects.requireNonNull(createdAt, "createdAt");
        username = normalizeUsername(username);
        if (!USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("Username must be 3-64 characters using letters, numbers, '.', '_' or '-'");
        }
        if (passwordHash.isBlank()) throw new IllegalArgumentException("Password hash must not be blank");
        if (version < 0) throw new IllegalArgumentException("User version must not be negative");
    }

    public static User create(String username, String passwordHash, Instant now) {
        return new User(UUID.randomUUID(), username, passwordHash, now, 0);
    }

    public static String normalizeUsername(String value) {
        Objects.requireNonNull(value, "username");
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
