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
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;

/** Encrypts user model credentials with an application-local key. */
@Component
public final class SecretCipher {
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;
    // User model keys are bounded to printable ASCII in ModelApiKeyPolicy.
    // Keep a small allowance for the nonce, authentication tag and Base64
    // expansion so a tampered database cannot make decrypt allocate an
    // unbounded byte array before the domain boundary rejects it.
    private static final int MAX_PLAINTEXT_BYTES = 4_096;
    private static final int MAX_PACKED_BYTES = NONCE_BYTES + TAG_BYTES + MAX_PLAINTEXT_BYTES;
    private static final int MAX_CIPHERTEXT_CHARS = ((MAX_PACKED_BYTES + 2) / 3) * 4;
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${offcanon.data-root}") String dataRoot,
                        ApplicationInstanceLock instanceLock) {
        try {
            java.util.Objects.requireNonNull(instanceLock, "instanceLock");
            Path root = Path.of(dataRoot).toAbsolutePath().normalize();
            if (Files.isSymbolicLink(root)) {
                throw new IllegalStateException("Offcanon data directory must not be a symbolic link");
            }
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) {
                throw new IllegalStateException("Offcanon data directory must not be a symbolic link");
            }
            Path keyPath = root.resolve("secret.key");
            byte[] raw;
            if (Files.exists(keyPath, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(keyPath) || !Files.isRegularFile(keyPath, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("Offcanon secret key must be a regular file");
                }
                try {
                    if (Files.size(keyPath) != KEY_BYTES) {
                        throw new IllegalStateException("Invalid Offcanon secret key");
                    }
                } catch (java.io.IOException error) {
                    throw new IllegalStateException("Unable to inspect Offcanon secret key", error);
                }
                raw = Files.readAllBytes(keyPath);
                if (raw.length != KEY_BYTES) throw new IllegalStateException("Invalid Offcanon secret key");
                tightenPermissions(keyPath);
            } else {
                raw = new byte[KEY_BYTES];
                random.nextBytes(raw);
                try {
                    Files.write(keyPath, raw, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                } catch (FileAlreadyExistsException race) {
                    // Another thread/process may have created the key between
                    // the existence check and CREATE_NEW. Re-open it through
                    // the same symlink/size checks instead of overwriting it.
                    if (Files.isSymbolicLink(keyPath)
                            || !Files.isRegularFile(keyPath, LinkOption.NOFOLLOW_LINKS)
                            || Files.size(keyPath) != KEY_BYTES) {
                        throw new IllegalStateException("Invalid Offcanon secret key", race);
                    }
                    raw = Files.readAllBytes(keyPath);
                    if (raw.length != KEY_BYTES) throw new IllegalStateException("Invalid Offcanon secret key");
                }
                tightenPermissions(keyPath);
            }
            key = new SecretKeySpec(raw, "AES");
        } catch (Exception error) {
            throw new IllegalStateException("Unable to initialise Offcanon secret storage", error);
        }
    }

    private void tightenPermissions(Path keyPath) {
        try {
            Files.setPosixFilePermissions(keyPath, EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException ignored) {
            // Windows ACLs are inherited from the application data directory.
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
            if (ciphertext.length() > MAX_CIPHERTEXT_CHARS) {
                throw new IllegalArgumentException("Encrypted credential is too large");
            }
            byte[] packed = Base64.getDecoder().decode(ciphertext);
            if (packed.length <= NONCE_BYTES + TAG_BYTES || packed.length > MAX_PACKED_BYTES) {
                throw new IllegalArgumentException("Invalid encrypted credential");
            }
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
