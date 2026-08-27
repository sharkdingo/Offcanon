package com.offcanon.infrastructure.promotion;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.port.PromotionPort;
import com.offcanon.project.domain.Project;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.workspace.domain.Snapshot;
import org.springframework.stereotype.Component;

import java.io.IOException;
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
    public PromotionResult apply(Project project, Snapshot base, Experiment experiment, Path candidate) {
        return apply(project, base, experiment, candidate, plan(project, base, experiment, candidate));
    }

    @Override
    public PromotionResult apply(Project project,
                                 Snapshot base,
                                 Experiment experiment,
                                 Path candidate,
                                 PromotionPlan expectedPlan) {
        Path canonical = project.canonicalPath().toAbsolutePath().normalize();
        Map<String, byte[]> before = files(base.materializedPath());
        Map<String, byte[]> after = files(candidate);
        List<Operation> operations = planOperations(canonical, before, after);
        if (!promotionPlan(operations).equals(expectedPlan)) {
            throw new DomainException("PROMOTION_CANDIDATE_MUTATED",
                    "Promotion candidate changed after verification and planning");
        }
        List<Operation> applied = new ArrayList<>();
        try {
            for (Operation operation : operations) {
                checkPromotionLease();
                ensureSafeCanonicalParent(canonical, operation.target().getParent());
                if (!sameState(operation.target(), operation.before())) {
                    throw new DomainException("STALE_DURING_PROMOTION", "Canonical preimage changed: " + operation.relativePath());
                }
                applyOperation(canonical, operation);
                applied.add(operation);
                checkPromotionLease();
            }
            for (Operation operation : operations) {
                checkPromotionLease();
                if (!sameState(operation.target(), operation.after())) {
                    throw new DomainException("PROMOTION_POSTCONDITION_FAILED", "Canonical differs from promotion candidate: " + operation.relativePath());
                }
            }
            return new PromotionResult(true, operations.stream().map(Operation::relativePath).toList(), fingerprint(after));
        } catch (RuntimeException failure) {
            rollback(applied);
            throw failure;
        }
    }

    private List<Operation> planOperations(Path canonical, Map<String, byte[]> before, Map<String, byte[]> after) {
        Set<String> paths = new HashSet<>(before.keySet());
        paths.addAll(after.keySet());
        List<Operation> operations = new ArrayList<>();
            for (String relative : paths.stream().sorted().toList()) {
            if (isSensitivePath(relative)) {
                throw new DomainException("PROMOTION_PROTECTED_PATH", "Refusing to promote internal or sensitive path: " + relative);
            }
            if (isRuntimePath(relative)) continue;
            byte[] expected = before.get(relative);
            byte[] next = after.get(relative);
            if (Arrays.equals(expected, next)) continue;
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
            Files.write(temporary, operation.after());
            try {
                Files.move(temporary, operation.target(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, operation.target(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new DomainException("PROMOTION_APPLY_FAILED", "Unable to apply " + operation.relativePath() + ": " + error.getMessage());
        }
    }

    private void rollback(List<Operation> applied) {
        for (int index = applied.size() - 1; index >= 0; index--) {
            Operation operation = applied.get(index);
            if (!sameState(operation.target(), operation.after())) {
                throw new DomainException("MANUAL_RECOVERY_REQUIRED", "Promotion interrupted after an external change at " + operation.relativePath());
            }
            try {
                if (operation.before() == null) {
                    Files.deleteIfExists(operation.target());
                } else {
                    Files.createDirectories(operation.target().getParent());
                    Files.write(operation.target(), operation.before());
                }
            } catch (IOException error) {
                throw new DomainException("MANUAL_RECOVERY_REQUIRED", "Unable to roll back " + operation.relativePath());
            }
        }
    }

    private void checkPromotionLease() {
        if (Thread.currentThread().isInterrupted()) {
            Thread.interrupted();
            throw new DomainException("PROMOTION_LOCK_LOST", "Promotion lock lease was lost during canonical apply");
        }
    }

    private Map<String, byte[]> files(Path root) {
        Map<String, byte[]> result = new HashMap<>();
        try {
            if (!Files.isDirectory(root)) {
                throw new DomainException("PROMOTION_WORKSPACE_MISSING", "Promotion workspace does not exist: " + root);
            }
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                    if (!directory.equals(root) && Files.isSymbolicLink(directory)) {
                        throw new DomainException("PROMOTION_SYMLINK_BLOCKED", "Symlink directory in promotion candidate: " + root.relativize(directory));
                    }
                    String relative = root.relativize(directory).toString().replace('\\', '/');
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
                    result.put(relative, Files.readAllBytes(file));
                    return FileVisitResult.CONTINUE;
                }
            });
            return result;
        } catch (IOException error) {
            throw new DomainException("PROMOTION_READ_FAILED", "Unable to read promotion candidate");
        }
    }

    private boolean sameState(Path path, byte[] expected) {
        if (expected == null) return !Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        try {
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Arrays.equals(expected, Files.readAllBytes(path));
        } catch (IOException error) {
            return false;
        }
    }

    private void ensureSafeCanonicalParent(Path canonical, Path parent) {
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
        String[] parts = relative.toLowerCase(Locale.ROOT).split("/");
        for (String part : parts) {
            if (part.equals(".git") || part.equals(".offcanon")) return true;
        }
        String file = parts.length == 0 ? relative : parts[parts.length - 1];
        return file.equals(".env") || file.startsWith(".env.");
    }

    private boolean isRuntimePath(String relative) {
        for (String part : relative.toLowerCase(Locale.ROOT).split("/")) {
            if (part.equals("node_modules") || part.equals("target") || part.equals("build")
                    || part.equals("dist") || part.equals(".idea") || part.equals(".vscode")) return true;
        }
        return false;
    }

    private String fingerprint(Map<String, byte[]> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String path : files.keySet().stream().sorted().toList()) {
                digest.update(path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(files.get(path));
                digest.update((byte) 0);
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private String hash(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private record Operation(String relativePath, Path target, byte[] before, byte[] after) {
    }
}
