package com.offcanon.shared.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitivePathPolicyTest {
    @Test
    void blocksCommonCredentialFilesAndDirectories() {
        List<String> sensitive = List.of(
                ".npmrc",
                "config/.pypirc",
                ".netrc",
                "_netrc",
                ".git-credentials",
                ".aws/credentials",
                ".AWS/CREDENTIALS",
                ".ssh/id_ed25519",
                ".kube/config",
                ".docker/config.json",
                ".config/gcloud/application_default_credentials.json",
                "credentials.json",
                "client_secret.json",
                "terraform.tfstate.backup",
                "certificates/server.pem",
                "keys/signing.key");

        for (String path : sensitive) {
            assertTrue(SensitivePathPolicy.isSensitiveRelativePath(path), path);
        }
        assertTrue(SensitivePathPolicy.containsSensitivePathReference("type .config\\gcloud\\config"));
    }

    @Test
    void blocksObviousShellReferencesToCredentialPaths() {
        List<String> commands = List.of(
                "cat .npmrc",
                "type config/.aws/credentials",
                "cat .ssh/id_rsa",
                "cat .config/gcloud/configurations/config_default",
                "read certs/server.pem",
                "print terraform.tfstate");

        for (String command : commands) {
            assertTrue(SensitivePathPolicy.containsSensitivePathReference(command), command);
        }
    }

    @Test
    void keepsTemplatesAndOrdinarySourceNamesAvailable() {
        List<String> safePaths = List.of(
                ".env.example",
                "config/.env.sample",
                ".env.template",
                "src/credentialsService.java",
                "src/settings.json",
                ".aws-cache/config",
                "gcloud/config");

        for (String path : safePaths) {
            assertFalse(SensitivePathPolicy.isSensitiveRelativePath(path), path);
        }
        assertFalse(SensitivePathPolicy.containsSensitivePathReference("cat .env.example"));
        assertFalse(SensitivePathPolicy.containsSensitivePathReference("cat src/settings.json"));
    }
}
