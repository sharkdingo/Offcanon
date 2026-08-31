package com.offcanon.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offcanon.identity.domain.User;
import com.offcanon.identity.domain.UserSettings;
import com.offcanon.identity.web.IdentityContext;
import com.offcanon.infrastructure.workspace.RuntimeRetentionService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettingsControllerTest {
    @Test
    void redactsSecretsInsideJsonPayloadsAndPlainDiagnostics() throws Exception {
        AuthApplicationServiceFixture fixture = new AuthApplicationServiceFixture();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("payload")) {
                return List.of(Map.of("payload", "{\"api_key\":\"json-api-secret\","
                        + "\"authorization\":\"Bearer json-token-secret\","
                        + "\"nested\":{\"clientSecret\":\"nested-secret\"},"
                        + "\"safe\":\"keep-me\","
                        + "\"provider\":\"https://provider.example/v1?api_key=query-api-secret\"}"));
            }
            if (sql.contains("stdout")) {
                return List.of(Map.of("stdout", "api_key=plain-api-secret\nkeep-me"));
            }
            return List.of();
        }).when(jdbc).queryForList(anyString(), any(Object[].class));

        SettingsController controller = new SettingsController(
                fixture.auth, fixture.identity, jdbc, mock(RuntimeRetentionService.class), new ObjectMapper());
        String exported = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(controller.export(fixture.request));

        assertFalse(exported.contains("json-api-secret"));
        assertFalse(exported.contains("json-token-secret"));
        assertFalse(exported.contains("nested-secret"));
        assertFalse(exported.contains("plain-api-secret"));
        assertFalse(exported.contains("query-api-secret"));
        assertFalse(exported.contains("saved-secret"));
        assertTrue(exported.contains("[REDACTED]"));
        assertTrue(exported.contains("keep-me"));
    }

    private static final class AuthApplicationServiceFixture {
        private final com.offcanon.identity.application.AuthApplicationService auth = mock(
                com.offcanon.identity.application.AuthApplicationService.class);
        private final IdentityContext identity = mock(IdentityContext.class);
        private final jakarta.servlet.http.HttpServletRequest request = mock(
                jakarta.servlet.http.HttpServletRequest.class);
        private final User user = new User(UUID.randomUUID(), "export-user", "password-hash",
                Instant.parse("2026-01-01T00:00:00Z"), 0);

        private AuthApplicationServiceFixture() {
            UserSettings settings = new UserSettings(user.id(), "system", "zh-CN",
                    "https://models.example/v1", "model", "saved-secret", 20, 600,
                    80_000, Instant.parse("2026-01-01T00:00:00Z"), 0);
            when(identity.requireUser(request)).thenReturn(user);
            when(auth.getSettings(user)).thenReturn(settings);
        }
    }
}
