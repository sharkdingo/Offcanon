package com.pico.project.domain;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Stable database and in-memory identity for an already resolved canonical project root. */
public final class CanonicalPathIdentity {
    private static final boolean CASE_INSENSITIVE = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    private CanonicalPathIdentity() {
    }

    public static String value(Path path) {
        String value = path.toAbsolutePath().normalize().toString().replace('\\', '/');
        return CASE_INSENSITIVE ? value.toLowerCase(Locale.ROOT) : value;
    }

    public static String key(Path path) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value(path).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
