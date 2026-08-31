package com.offcanon.shared.domain;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One conservative predicate for files that must never enter agent context
 * or an experiment promotion. The check is relative-path based so it can be
 * reused by filesystem traversal, snapshot, diff and promotion adapters.
 */
public final class SensitivePathPolicy {
    private static final Set<String> SAFE_ENV_TEMPLATES = Set.of(
            ".env.example", ".env.sample", ".env.template");
    /** Credential-bearing directory names that should never enter an agent context. */
    private static final Set<String> SENSITIVE_DIRECTORY_NAMES = Set.of(
            ".aws", ".ssh", ".gnupg", ".kube", ".docker", ".azure", ".terraform");
    /** Explicit credential file names; avoid broad substring matching of ordinary source files. */
    private static final Set<String> SENSITIVE_FILE_NAMES = Set.of(
            ".npmrc", ".pypirc", ".netrc", "_netrc", ".git-credentials", ".gitconfig",
            ".pgpass", ".my.cnf", ".credentials", ".authinfo",
            "credentials", "credentials.json", "credentials.yaml", "credentials.yml", "credentials.ini",
            "credential", "credential.json", "credential.yaml", "credential.yml",
            "auth.json", "auth.yaml", "auth.yml", "tokens.json", "token.json",
            "secrets.json", "secrets.yaml", "secrets.yml", "secret.json", "secret.yaml", "secret.yml",
            "client_secret.json", "client_secret.yaml", "client_secret.yml",
            "service-account.json", "service_account.json", "application_default_credentials.json",
            "accesstokens.json", "azureprofile.json",
            "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", "id_xmss",
            "private_key", "private-key", "privatekey",
            "terraform.tfstate", "terraform.tfstate.backup", "terraform.tfvars", "terraform.tfvars.json");
    private static final Pattern SENSITIVE_REFERENCE = Pattern.compile(
            "(?i)(^|[\\s\\\"'=:/\\\\])(" + sensitiveReferenceAlternatives()
                    + ")(?=$|[\\s\\\"'&|<>:/\\\\])");

    private SensitivePathPolicy() {
    }

    public static boolean isSensitiveRelativePath(String relative) {
        if (relative == null || relative.isBlank()) return false;
        String[] parts = relative.replace('\\', '/').split("/");
        for (String part : parts) {
            String normalized = part.toLowerCase(Locale.ROOT);
            if (SENSITIVE_DIRECTORY_NAMES.contains(normalized)) return true;
            if (normalized.equals("gcloud") && hasConfigParent(parts, part)) return true;
            if (isSensitiveFileName(normalized)) return true;
        }
        return false;
    }

    /** Detects an obvious shell reference without pretending to parse a shell. */
    public static boolean containsSensitivePathReference(String command) {
        if (command == null || command.isBlank()) return false;
        var matcher = SENSITIVE_REFERENCE.matcher(command);
        while (matcher.find()) {
            if (isSensitiveRelativePath(matcher.group(2))) return true;
        }
        return false;
    }

    private static boolean hasConfigParent(String[] parts, String current) {
        for (int index = 1; index < parts.length; index++) {
            if (parts[index].equals(current) && ".config".equalsIgnoreCase(parts[index - 1])) return true;
        }
        return false;
    }

    private static boolean isSensitiveFileName(String normalized) {
        if (SENSITIVE_FILE_NAMES.contains(normalized)) return true;
        if (normalized.equals(".env")) return true;
        if (normalized.startsWith(".env.")) return !SAFE_ENV_TEMPLATES.contains(normalized);
        // Terraform state and key/certificate containers routinely contain
        // credentials; cover variants without matching ordinary source files.
        return normalized.endsWith(".tfstate")
                || normalized.endsWith(".tfstate.backup")
                || normalized.endsWith(".p12")
                || normalized.endsWith(".pfx")
                || normalized.endsWith(".pem")
                || normalized.endsWith(".key");
    }

    private static String sensitiveReferenceAlternatives() {
        StringBuilder alternatives = new StringBuilder("\\.env(?:\\.[a-z0-9_-]+)?");
        SENSITIVE_DIRECTORY_NAMES.stream()
                .sorted((left, right) -> Integer.compare(right.length(), left.length()))
                .forEach(name -> alternatives.append('|').append(Pattern.quote(name)));
        SENSITIVE_FILE_NAMES.stream()
                .sorted((left, right) -> Integer.compare(right.length(), left.length()))
                .forEach(name -> alternatives.append('|').append(Pattern.quote(name)));
        alternatives.append("|\\.config[/\\\\]gcloud");
        alternatives.append("|[A-Za-z0-9_.-]+\\.tfstate(?:\\.backup)?");
        alternatives.append("|[A-Za-z0-9_.-]+\\.(?:p12|pfx|pem|key)");
        return alternatives.toString();
    }
}
