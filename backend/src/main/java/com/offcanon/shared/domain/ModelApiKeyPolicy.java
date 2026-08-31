package com.offcanon.shared.domain;

/**
 * Validation shared by persisted settings and outbound model requests.
 *
 * <p>Bearer credentials are placed in an HTTP header by the model adapter.
 * Restricting them to printable ASCII makes the boundary deterministic across
 * JDK HTTP implementations and rejects header injection, invisible Unicode
 * characters, and values that would otherwise fail only after a run starts.</p>
 */
public final class ModelApiKeyPolicy {
    public static final int MAX_LENGTH = 4_096;

    private ModelApiKeyPolicy() {
    }

    /** Returns a trimmed key, or an empty value when no key is configured. */
    public static String normalize(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Model API key is too long");
        }
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            // HTTP bearer credentials must not contain whitespace, controls,
            // delimiters, or non-ASCII code points at this boundary.
            if (current < 0x21 || current > 0x7E) {
                throw new IllegalArgumentException(
                        "Model API key must contain printable ASCII characters only");
            }
        }
        return normalized;
    }

    public static boolean isValid(String value) {
        try {
            normalize(value);
            return true;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}
