package com.offcanon.infrastructure.sqlite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Prevents two desktop server processes from writing the same SQLite/runtime root. */
@Component
public final class ApplicationInstanceLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    public ApplicationInstanceLock(@Value("${offcanon.data-root}") String dataRoot) {
        try {
            Path root = Path.of(dataRoot).toAbsolutePath().normalize();
            Files.createDirectories(root);
            channel = FileChannel.open(root.resolve("instance.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw new IllegalStateException("Another Offcanon instance is already using " + root);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Unable to acquire Offcanon instance lock", error);
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
