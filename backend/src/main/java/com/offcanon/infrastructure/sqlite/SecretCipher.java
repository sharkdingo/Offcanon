package com.offcanon.infrastructure.sqlite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;

/** Encrypts user model credentials with an application-local key. */
@Component
public final class SecretCipher {
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${offcanon.data-root}") String dataRoot,
                        ApplicationInstanceLock instanceLock) {
        try {
            java.util.Objects.requireNonNull(instanceLock, "instanceLock");
            Path root = Path.of(dataRoot).toAbsolutePath().normalize();
            Files.createDirectories(root);
            Path keyPath = root.resolve("secret.key");
            byte[] raw;
            if (Files.exists(keyPath)) {
                raw = Files.readAllBytes(keyPath);
                if (raw.length != KEY_BYTES) throw new IllegalStateException("Invalid Offcanon secret key");
            } else {
                raw = new byte[KEY_BYTES];
                random.nextBytes(raw);
                Files.write(keyPath, raw);
                try {
                    Files.setPosixFilePermissions(keyPath, EnumSet.of(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE));
                } catch (UnsupportedOperationException ignored) {
                    // Windows ACLs are inherited from the application data directory.
                }
            }
            key = new SecretKeySpec(raw, "AES");
        } catch (Exception error) {
            throw new IllegalStateException("Unable to initialise Offcanon secret storage", error);
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return "";
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] packed = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, packed, 0, nonce.length);
            System.arraycopy(encrypted, 0, packed, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to encrypt model credential", error);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) return "";
        try {
            byte[] packed = Base64.getDecoder().decode(ciphertext);
            if (packed.length <= NONCE_BYTES) throw new IllegalArgumentException("Invalid encrypted credential");
            byte[] nonce = java.util.Arrays.copyOfRange(packed, 0, NONCE_BYTES);
            byte[] encrypted = java.util.Arrays.copyOfRange(packed, NONCE_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to decrypt model credential", error);
        }
    }
}
