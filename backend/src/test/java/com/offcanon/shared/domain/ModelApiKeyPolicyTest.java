package com.offcanon.shared.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelApiKeyPolicyTest {
    @Test
    void trimsPrintableAsciiKeys() {
        assertEquals("sk-test", ModelApiKeyPolicy.normalize("  sk-test  "));
        assertTrue(ModelApiKeyPolicy.isValid("sk-test_123"));
        assertTrue(ModelApiKeyPolicy.isValid(""));
    }

    @Test
    void rejectsHeaderUnsafeCharactersAndOversizedValues() {
        assertFalse(ModelApiKeyPolicy.isValid("key with spaces"));
        assertFalse(ModelApiKeyPolicy.isValid("key\u000bvalue"));
        assertFalse(ModelApiKeyPolicy.isValid("密钥"));
        assertThrows(IllegalArgumentException.class,
                () -> ModelApiKeyPolicy.normalize("x".repeat(ModelApiKeyPolicy.MAX_LENGTH + 1)));
    }
}
