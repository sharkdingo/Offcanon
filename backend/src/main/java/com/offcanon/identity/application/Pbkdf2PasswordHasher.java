package com.offcanon.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Component;

/** PBKDF2 password storage using a random salt and a versioned textual format. */
@Component
public final class Pbkdf2PasswordHasher implements PasswordHasher {
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2_sha256";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private final SecureRandom random;

    public Pbkdf2PasswordHasher() {
        this(new SecureRandom());
    }

    Pbkdf2PasswordHasher(SecureRandom random) {
        this.random = random;
    }

    @Override
    public String hash(String password) {
        validatePassword(password);
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] digest = derive(password, salt, ITERATIONS, KEY_BITS);
        return PREFIX + "$" + ITERATIONS + "$"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(salt) + "$"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    @Override
    public boolean matches(String password, String encoded) {
        if (password == null || encoded == null) return false;
        try {
            String[] pieces = encoded.split("\\$", -1);
            if (pieces.length != 4 || !PREFIX.equals(pieces[0])) return false;
            int iterations = Integer.parseInt(pieces[1]);
            if (iterations < 100_000 || iterations > 2_000_000) return false;
            byte[] salt = Base64.getUrlDecoder().decode(pieces[2]);
            byte[] expected = Base64.getUrlDecoder().decode(pieces[3]);
            if (salt.length < 16 || expected.length == 0) return false;
            byte[] actual = derive(password, salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(actual, expected);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private byte[] derive(String password, byte[] salt, int iterations, int keyBits) {
        try {
            PBEKeySpec specification = new PBEKeySpec(password.toCharArray(), salt, iterations, keyBits);
            try {
                return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(specification).getEncoded();
            } finally {
                specification.clearPassword();
            }
        } catch (Exception error) {
            throw new IllegalStateException("PBKDF2 password hashing is unavailable", error);
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 256) {
            throw new IllegalArgumentException("Password must be 8-256 characters");
        }
    }
}
