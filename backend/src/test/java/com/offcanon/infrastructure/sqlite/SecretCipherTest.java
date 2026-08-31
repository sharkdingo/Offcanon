package com.offcanon.infrastructure.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretCipherTest {
    @TempDir
    Path temp;

    @Test
    void rejectsOversizedCiphertextBeforeAttemptingDecryption() {
        try (ApplicationInstanceLock lock = new ApplicationInstanceLock(temp.toString())) {
            SecretCipher cipher = new SecretCipher(temp.toString(), lock);
            assertEquals("", cipher.decrypt(""));
            assertThrows(IllegalStateException.class,
                    () -> cipher.decrypt("A".repeat(20_000)));
        }
    }
}
