package com.offcanon.agent.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelRequestValidationTest {
    @Test
    void rejectsControlCharactersInApiKeysBeforeHttpHeaderConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new ModelRequest(
                List.of(ModelMessage.user("test")), List.of(), Duration.ofSeconds(5),
                "http://127.0.0.1:8080/v1", "model", "key\nforged-header"));
        assertThrows(IllegalArgumentException.class, () -> new ModelRequest(
                List.of(ModelMessage.user("test")), List.of(), Duration.ofSeconds(5),
                "http://127.0.0.1:8080/v1", "model", "key\0value"));
        assertThrows(IllegalArgumentException.class, () -> new ModelRequest(
                List.of(ModelMessage.user("test")), List.of(), Duration.ofSeconds(5),
                "http://127.0.0.1:8080/v1", "model", "密钥"));
    }
}
