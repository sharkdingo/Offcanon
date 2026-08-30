package com.offcanon.shared.domain;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Canonical validation for credential-bearing model provider endpoints.
 *
 * <p>The endpoint is a base URL for an OpenAI-compatible chat completions
 * API. Query strings, fragments and embedded credentials are deliberately
 * rejected because they make the destination ambiguous when the server adds
 * its API path and bearer token.</p>
 */
public final class ModelEndpointPolicy {
    private ModelEndpointPolicy() {
    }

    /**
     * Returns a stable allowlist key, or {@code null} when the value is not a
     * valid HTTP(S) endpoint. A trailing {@code /chat/completions} is accepted
     * and normalized back to the provider base URL.
     */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.trim());
            if (uri.isOpaque()
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                return null;
            }
            int port = uri.getPort();
            // URI accepts numerically out-of-range ports, while the HTTP
            // client rejects them only when a request is built. Fail at the
            // shared settings/adapter boundary instead of at run time.
            if (port > 65_535) return null;
            if (port < 0) port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            String path = uri.getPath() == null ? "" : uri.getPath();
            while (path.endsWith("/") && !path.isEmpty()) path = path.substring(0, path.length() - 1);
            String completionsSuffix = "/chat/completions";
            if (path.endsWith(completionsSuffix)) path = path.substring(0, path.length() - completionsSuffix.length());
            while (path.endsWith("/") && !path.isEmpty()) path = path.substring(0, path.length() - 1);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + host + ":" + port + path;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    public static boolean isValid(String value) {
        return normalize(value) != null;
    }

    /**
     * Checks whether a user-selected endpoint is one of the explicitly
     * trusted destinations that may receive the server-side model secret.
     * The process environment is evaluated at call time so a deployment can
     * provide its primary endpoint without duplicating it in Spring config.
     */
    public static boolean isAllowed(String requested,
                                    String configuredBaseUrl,
                                    String configuredAllowedBaseUrls,
                                    String environmentBaseUrl) {
        String normalized = normalize(requested);
        return normalized != null
                && allowedEndpoints(configuredBaseUrl, configuredAllowedBaseUrls, environmentBaseUrl)
                .contains(normalized);
    }

    /** Returns the normalized endpoint set used by both Settings and the model adapter. */
    public static Set<String> allowedEndpoints(String configuredBaseUrl,
                                               String configuredAllowedBaseUrls,
                                               String environmentBaseUrl) {
        LinkedHashSet<String> endpoints = new LinkedHashSet<>();
        addEndpoint(endpoints, configuredBaseUrl, "configured model endpoint");
        addEndpoint(endpoints, environmentBaseUrl, "environment model endpoint");
        if (configuredAllowedBaseUrls != null) {
            for (String value : configuredAllowedBaseUrls.split(",")) {
                addEndpoint(endpoints, value, "model endpoint allowlist entry");
            }
        }
        return Collections.unmodifiableSet(endpoints);
    }

    private static void addEndpoint(Set<String> endpoints, String value, String label) {
        if (value == null || value.isBlank()) return;
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(label
                    + " must be a valid HTTP(S) base URL without credentials, query or fragment");
        }
        endpoints.add(normalized);
    }
}
