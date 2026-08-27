package com.offcanon.identity.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Pbkdf2PasswordHasherTest {
    private final Pbkdf2PasswordHasher hasher = new Pbkdf2PasswordHasher();

    @Test
    void storesASaltedDigestAndMatchesOnlyTheOriginalPassword() {
        String first = hasher.hash("correct horse battery staple");
        String second = hasher.hash("correct horse battery staple");

        assertTrue(first.startsWith("pbkdf2_sha256$"));
        assertNotEquals("correct horse battery staple", first);
        assertNotEquals(first, second, "Each password hash must use a fresh salt");
        assertTrue(hasher.matches("correct horse battery staple", first));
        assertFalse(hasher.matches("incorrect password", first));
    }

    @Test
    void rejectsPasswordsOutsideTheLocalAccountPolicy() {
        assertThrows(IllegalArgumentException.class, () -> hasher.hash("short"));
        assertFalse(hasher.matches("correct horse battery staple", "not-a-pbkdf2-value"));
    }
}
