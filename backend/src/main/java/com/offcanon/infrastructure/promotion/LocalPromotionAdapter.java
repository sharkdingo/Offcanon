package com.offcanon.infrastructure.promotion;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.infrastructure.filesystem.GitFileMode;
import com.offcanon.port.PromotionPort;
import com.offcanon.port.PromotionLockPort;
import com.offcanon.project.domain.Project;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.shared.domain.SensitivePathPolicy;
import com.offcanon.workspace.domain.Snapshot;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

@Component
public class LocalPromotionAdapter implements PromotionPort {
    private static final String ABSENT = "ABSENT";
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private final PromotionLockPort promotionLock;

    @Autowired
    public LocalPromotionAdapter(PromotionLockPort promotionLock) {
        this.promotionLock = java.util.Objects.requireNonNull(promotionLock, "promotionLock");
    }

    @Override
    public PromotionPlan plan(Project project, Snapshot base, Experiment experiment, Path candidate) {
        Path canonical = project.canonicalPath().toAbsolutePath().normalize();
        List<Operation> operations = planOperations(canonical, files(base.materializedPath()), files(candidate));
        return promotionPlan(operations);
    }

    private PromotionPlan promotionPlan(List<Operation> operations) {
        Map<String, String> preimages = new java.util.LinkedHashMap<>();
        Map<String, String> postimages = new java.util.LinkedHashMap<>();
        for (Operation operation : operations) {
            preimages.put(operation.relativePath(), operation.before() == null ? ABSENT : hash(operation.before()));
            postimages.put(operation.relativePath(), operation.after() == null ? ABSENT : hash(operation.after()));
        }
        return new PromotionPlan(operations.stream().map(Operation::relativePath).toList(), preimages, postimages);
    }

    @Override
    public PromotionResult apply(Project project,
                                  Snapshot base,
                                  Experiment experiment,
                                  Path candidate,
                                  PromotionPlan expectedPlan) {
        checkPromotionLease(project);
        Path canonical = project.canonicalPath().toAbsolutePath().normalize();
        Map<String, FileState> before = files(base.materializedPath());
        Map<String, FileState> after = files(candidate);
        List<Operation> operations = planOperations(canonical, before, after);
        if (!promotionPlan(operations).equals(expectedPlan)) {
            throw new DomainException("PROMOTION_CANDIDATE_MUTATED",
                    "Promotion candidate changed after verification and planning");
        }
        List<Operation> applied = new ArrayList<>();
        try {
            for (Operation operation : operations) {
                checkPromotionLease(project);
                ensureSafeCanonicalParent(canonical, operation.target().getParent());
                if (!sameState(operation.target(), operation.before())) {
                    throw new DomainException("STALE_DURING_PROMOTION", "Canonical preimage changed: " + operation.relativePath());
                }
                applyOperation(canonical, operation);
                applied.add(operation);
                checkPromotionLease(project);
            }
            for (Operation operation : operations) {
                checkPromotionLease(project);
                if (!sameState(operation.target(), operation.after())) {
                    throw new DomainException("PROMOTION_POSTCONDITION_FAILED", "Canonical differs from promotion candidate: " + operation.relativePath());
                }
            }
            checkPromotionLease(project);
            return new PromotionResult(true, operations.stream().map(Operation::relativePath).toList());
        } catch (RuntimeException failure) {
            // Once the distributed lease is gone, rollback would be another
            // canonical write without ownership. Leave the durable journal in
            // an explicit recovery state instead of guessing that rollback is
            // still safe.
            if (failure instanceof DomainException domain
                    && "PROMOTION_LOCK_LOST".equals(domain.code())) {
                throw failure;
            }
            rollback(project, canonical, applied);
            throw failure;
        }
    }

    private List<Operation> planOperations(Path canonical, Map<String, FileState> before, Map<String, FileState> after) {
        Set<String> paths = new HashSet<>(before.keySet());
        paths.addAll(after.keySet());
        List<Operation> operations = new ArrayList<>();
            for (String relative : paths.stream().sorted().toList()) {
            if (isSensitivePath(relative)) {
                throw new DomainException("PROMOTION_PROTECTED_PATH", "Refusing to promote internal or sensitive path: " + relative);
            }
            if (isRuntimePath(relative)) continue;
            FileState expected = before.get(relative);
            FileState next = after.get(relative);
            if (sameState(expected, next)) continue;
            Path target = canonical.resolve(relative).normalize();
            if (!target.startsWith(canonical)) {
                throw new DomainException("PROMOTION_PATH_ESCAPE", "Promotion path escapes canonical workspace");
            }
            ensureSafeCanonicalParent(canonical, target.getParent());
            if (!sameState(target, expected)) {
                throw new DomainException("STALE_DURING_PROMOTION", "Canonical preimage changed: " + relative);
            }
            operations.add(new Operation(relative, target, expected, next));
        }
        return operations;
    }

    private void applyOperation(Path canonical, Operation operation) {
        try {
            ensureSafeCanonicalParent(canonical, operation.target().getParent());
            if (operation.after() == null) {
                Files.deleteIfExists(operation.target());
                return;
            }
            Files.createDirectories(operation.target().getParent());
            Path temporary = Files.createTempFile(operation.target().getParent(), ".offcanon-promote-", ".tmp");
            Files.write(temporary, operation.after().content());
            GitFileMode.apply(temporary, operation.after().mode());
            try {
                Files.move(temporary, operation.target(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, operation.target(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new DomainException("PROMOTION_APPLY_FAILED", "Unable to apply " + operation.relativePath() + ": " + error.getMessage());
        }
    }

    private void rollback(Project project, Path canonical, List<Operation> applied) {
        for (int index = applied.size() - 1; index >= 0; index--) {
            Operation operation = applied.get(index);
            // Rollback is still a canonical write.  The distributed lease may
            // have expired while the original operation was failing, so every
            // rollback step must prove ownership and re-check its parent path.
            checkPromotionLease(project);
            ensureSafeCanonicalParent(canonical, operation.target().getParent());
            if (!sameState(operation.target(), operation.after())) {
                throw new DomainException("MANUAL_RECOVERY_REQUIRED", "Promotion interrupted after an external change at " + operation.relativePath());
            }
            try {
                if (operation.before() == null) {
                    Files.deleteIfExists(operation.target());
                } else {
                    Files.createDirectories(operation.target().getParent());
                    Files.write(operation.target(), operation.before().content());
                    GitFileMode.apply(operation.target(), operation.before().mode());
                }
                checkPromotionLease(project);
            } catch (IOException error) {
                throw new DomainException("MANUAL_RECOVERY_REQUIRED", "Unable to roll back " + operation.relativePath());
            }
        }
    }

    private void checkPromotionLease(Project project) {
        if (Thread.currentThread().isInterrupted()) {
            Thread.interrupted();
            throw new DomainException("PROMOTION_LOCK_LOST", "Promotion lock lease was lost during canonical apply");
        }
        promotionLock.assertHeld(project.id());
    }

    private Map<String, FileState> files(Path root) {
        Map<String, FileState> result = new HashMap<>();
        try {
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new DomainException("PROMOTION_WORKSPACE_MISSING", "Promotion workspace does not exist: " + root);
            }
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                    if (!directory.equals(root) && Files.isSymbolicLink(directory)) {
                        throw new DomainException("PROMOTION_SYMLINK_BLOCKED", "Symlink directory in promotion candidate: " + root.relativize(directory));
                    }
                    String relative = root.relativize(directory).toString().replace('\\', '/');
                    if (relative.equals(".git")) return FileVisitResult.SKIP_SUBTREE;
                    if (!directory.equals(root) && isSensitivePath(relative)) {
                        throw new DomainException("PROMOTION_PROTECTED_PATH",
                                "Refusing protected directory in promotion candidate: " + root.relativize(directory));
                    }
                    if (!directory.equals(root) && isRuntimePath(relative)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(file)) {
                        throw new DomainException("PROMOTION_SYMLINK_BLOCKED", "Symlink in promotion candidate: " + root.relativize(file));
                    }
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    if (isSensitivePath(relative)) {
                        throw new DomainException("PROMOTION_PROTECTED_PATH",
                                "Refusing protected path in promotion candidate: " + relative);
                    }
                    if (isRuntimePath(relative)) return FileVisitResult.CONTINUE;
                    result.put(relative, new FileState(readBounded(file), GitFileMode.read(file)));
                    return FileVisitResult.CONTINUE;
                }
            });
            return result;
        } catch (IOException error) {
            throw new DomainException("PROMOTION_READ_FAILED", "Unable to read promotion candidate");
        }
    }

    private boolean sameState(Path path, FileState expected) {
        if (expected == null) return !Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || expected.mode() != GitFileMode.read(path)) return false;
            // Never let a concurrent replacement turn this precondition check
            // into an unbounded read.  Snapshot files are capped at 20 MiB;
            // requiring the same byte length before a bounded stream read also
            // makes a changed large file fail closed.
            if (Files.size(path) != expected.content().length) return false;
            try (InputStream input = Files.newInputStream(path)) {
                byte[] actual = input.readNBytes(expected.content().length + 1);
                return actual.length == expected.content().length
                        && Arrays.equals(expected.content(), actual);
            }
        } catch (IOException error) {
            return false;
        }
    }

    private byte[] readBounded(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) {
            throw new DomainException("PROMOTION_FILE_TOO_LARGE",
                    "Promotion file exceeds 20 MiB safety limit: " + file.getFileName());
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] content = input.readNBytes((int) MAX_FILE_BYTES + 1);
            if (content.length > MAX_FILE_BYTES) {
                throw new DomainException("PROMOTION_FILE_TOO_LARGE",
                        "Promotion file exceeds 20 MiB safety limit: " + file.getFileName());
            }
            return content;
        }
    }

    private boolean sameState(FileState left, FileState right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return left.mode() == right.mode() && Arrays.equals(left.content(), right.content());
    }

    private void ensureSafeCanonicalParent(Path canonical, Path parent) {
        if (Files.isSymbolicLink(canonical) || !Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)) {
            throw new DomainException("PROMOTION_PATH_INVALID", "Canonical workspace root must be a real directory");
        }
        Path current = parent;
        while (current != null && current.startsWith(canonical)) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new DomainException("PROMOTION_SYMLINK_BLOCKED", "Refusing to promote through symlink: " + canonical.relativize(current));
            }
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    if (!current.toRealPath().startsWith(canonical.toRealPath())) {
                        throw new DomainException("PROMOTION_PATH_ESCAPE", "Canonical parent resolves outside project");
                    }
                } catch (IOException error) {
                    throw new DomainException("PROMOTION_PATH_INVALID", "Unable to resolve canonical parent");
                }
                return;
            }
            current = current.getParent();
        }
        throw new DomainException("PROMOTION_PATH_ESCAPE", "Promotion path escapes canonical workspace");
    }

    private boolean isSensitivePath(String relative) {
        String[] parts = relative.toLowerCase(java.util.Locale.ROOT).split("/");
        for (String part : parts) {
            if (part.equals(".git") || part.equals(".offcanon")) return true;
        }
        return SensitivePathPolicy.isSensitiveRelativePath(relative);
    }

    private boolean isRuntimePath(String relative) {
        for (String part : relative.toLowerCase(Locale.ROOT).split("/")) {
            if (part.equals("node_modules") || part.equals("target") || part.equals("build")
                    || part.equals("dist") || part.equals(".idea") || part.equals(".vscode")) return true;
        }
        return false;
    }

    private String hash(FileState state) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Integer.toOctalString(state.mode()).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(state.content());
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private record FileState(byte[] content, int mode) {
    }

    private record Operation(String relativePath, Path target, FileState before, FileState after) {
    }
}
