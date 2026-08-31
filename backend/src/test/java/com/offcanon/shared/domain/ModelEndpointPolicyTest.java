package com.offcanon.shared.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelEndpointPolicyTest {
    @Test
    void normalizesHttpEndpointsAndTheChatCompletionsSuffix() {
        assertEquals("https://models.example:443/v1",
                ModelEndpointPolicy.normalize(" HTTPS://MODELS.EXAMPLE/v1/ "));
        assertEquals("https://models.example:443/v1",
                ModelEndpointPolicy.normalize("https://models.example/v1/chat/completions"));
        assertEquals("http://[::1]:8080/v1",
                ModelEndpointPolicy.normalize("http://[::1]:8080/v1"));
    }

    @Test
    void rejectsAmbiguousOrMalformedDestinations() {
        for (String value : new String[]{
                null,
                "",
                "   ",
                "ftp://models.example/v1",
                "models.example/v1",
                "https://user:password@models.example/v1",
                "https://models.example/v1?api-version=1",
                "https://models.example/v1#fragment",
                "https://models.example:65536/v1",
                "https://models.example:-1/v1",
                "https://models.example/%0Asecret",
                "https://models.example/%00secret",
                "https://models.example/%3Fapi_key=secret",
                "https://models.example/%23fragment",
                "https://models.example/v1/%2E%2E/secret",
                "https://models.example/%E2%80%A8secret"}) {
            assertNull(ModelEndpointPolicy.normalize(value), String.valueOf(value));
        }
    }

    @Test
    void permitsExplicitLocalProviderEndpoints() {
        assertTrue(ModelEndpointPolicy.isValid("http://127.0.0.1:11434/v1"));
        assertTrue(ModelEndpointPolicy.isValid("http://localhost:11434/v1"));
        assertFalse(ModelEndpointPolicy.isValid("https://models.example/v1?"));
    }
}
