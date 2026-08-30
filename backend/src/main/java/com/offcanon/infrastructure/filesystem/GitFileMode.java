package com.offcanon.infrastructure.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * The only filesystem mode bit represented by a Git regular-file tree entry.
 * Git records regular files as either 100644 or 100755; other POSIX bits are
 * local filesystem metadata and must not become promotion state.
 */
public final class GitFileMode {
    public static final int REGULAR = 0100644;
    public static final int EXECUTABLE = 0100755;

    private GitFileMode() {
    }

    /**
     * Reads the Git executable bit. Filesystems without POSIX attributes are
     * intentionally regular-only, matching Git's normal Windows behaviour.
     */
    public static int read(Path path) throws IOException {
        PosixFileAttributeView view = view(path);
        if (view == null) return REGULAR;
        return view.readAttributes().permissions().contains(PosixFilePermission.OWNER_EXECUTE)
                ? EXECUTABLE : REGULAR;
    }

    /**
     * Whether the provider exposes the POSIX mode needed to stage Git's
     * executable bit from a working tree. Non-POSIX providers intentionally
     * leave Git's own mode detection in control.
     */
    public static boolean supportsPosixAttributes(Path path) {
        return view(path) != null;
    }

    /** Applies the canonical Git executable state, preserving read/write bits. */
    public static void apply(Path path, int mode) throws IOException {
        if (mode != REGULAR && mode != EXECUTABLE) {
            throw new IllegalArgumentException("Unsupported Git file mode: " + mode);
        }
        PosixFileAttributeView view = view(path);
        if (view == null) return;
        Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        permissions.addAll(view.readAttributes().permissions());
        Set<PosixFilePermission> executeBits = Set.of(
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_EXECUTE);
        if (mode == EXECUTABLE) {
            // Git uses the owner execute bit as its tracked signal. Keep any
            // existing group/other permissions instead of broadening access.
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
        } else {
            permissions.removeAll(executeBits);
        }
        view.setPermissions(permissions);
    }

    private static PosixFileAttributeView view(Path path) {
        return Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    }
}
