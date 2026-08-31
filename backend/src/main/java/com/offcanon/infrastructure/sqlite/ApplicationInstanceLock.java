package com.offcanon.infrastructure.sqlite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

/** Prevents two desktop server processes from writing the same SQLite/runtime root. */
@Component
public final class ApplicationInstanceLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    public ApplicationInstanceLock(@Value("${offcanon.data-root}") String dataRoot) {
        try {
            Path root = Path.of(dataRoot).toAbsolutePath().normalize();
            if (Files.isSymbolicLink(root)) {
                throw new IllegalStateException("Offcanon data directory must not be a symbolic link");
            }
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Offcanon data directory must be a real directory");
            }
            tightenDirectoryPermissions(root);
            Path lockPath = root.resolve("instance.lock");
            if (Files.isSymbolicLink(lockPath)
                    || (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS))) {
                throw new IllegalStateException("Offcanon instance lock must be a regular file");
            }
            channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            // A pre-existing lock file may have inherited broader permissions
            // before this process started. Tighten it after opening as well as
            // on first creation; unsupported POSIX attributes are normal on
            // Windows where directory ACLs remain authoritative.
            tightenLockPermissions(lockPath);
            lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw new IllegalStateException("Another Offcanon instance is already using " + root);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Unable to acquire Offcanon instance lock", error);
        }
    }

    private void tightenDirectoryPermissions(Path root) {
        try {
            Files.setPosixFilePermissions(root, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and other providers rely on inherited ACLs.
        }
    }

    private void tightenLockPermissions(Path lockPath) {
        try {
            Files.setPosixFilePermissions(lockPath, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and other providers rely on inherited ACLs.
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
            channel.close();
        } catch (IOException ignored) {
            // Shutdown is best effort; the OS releases the lock with the process.
        }
    }
}
