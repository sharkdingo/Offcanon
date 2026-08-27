package com.offcanon.identity.application;

import com.offcanon.identity.domain.UserSettings;
import com.offcanon.infrastructure.memory.InMemoryAuthSessionRepository;
import com.offcanon.infrastructure.memory.InMemoryUserRepository;
import com.offcanon.infrastructure.memory.InMemoryUserSettingsRepository;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.shared.web.UnauthorizedException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthApplicationServiceTest {
    @Test
    void registersLogsInAndKeepsOnlyDerivedCredentials() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryUserRepository users = new InMemoryUserRepository();
        InMemoryUserSettingsRepository settings = new InMemoryUserSettingsRepository();
        InMemoryAuthSessionRepository sessions = new InMemoryAuthSessionRepository();
        AuthApplicationService auth = service(users, settings, sessions, clock, Duration.ofHours(2));

        AuthApplicationService.AuthResult registered = auth.register(" Alice ", "correct horse battery staple");
        assertEquals("alice", registered.user().username());
        assertNotEquals("correct horse battery staple", registered.user().passwordHash());
        assertTrue(new Pbkdf2PasswordHasher().matches("correct horse battery staple", registered.user().passwordHash()));
        assertEquals(UserSettings.defaults(registered.user().id(), clock.now()), auth.getSettings(registered.user()));

        AuthApplicationService.AuthResult loggedIn = auth.login("ALICE", "correct horse battery staple");
        assertEquals(registered.user().id(), loggedIn.user().id());
        assertThrows(UnauthorizedException.class, () -> auth.login("alice", "wrong password"));
        assertThrows(DomainException.class, () -> auth.register("alice", "another correct password"));
    }

    @Test
    void hashesBearerTokensAndLogoutInvalidatesTheSession() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryUserRepository users = new InMemoryUserRepository();
        InMemoryUserSettingsRepository settings = new InMemoryUserSettingsRepository();
        InMemoryAuthSessionRepository sessions = new InMemoryAuthSessionRepository();
        AuthApplicationService auth = service(users, settings, sessions, clock, Duration.ofHours(2));

        AuthApplicationService.AuthResult result = auth.register("alice", "correct horse battery staple");
        assertTrue(sessions.findByTokenHash(sha256(result.token())).isPresent());
        assertTrue(sessions.findByTokenHash(result.token()).isEmpty(), "Raw bearer token must never be a persistence key");
        assertEquals(result.user().id(), auth.authenticate("Bearer " + result.token()).id());

        auth.logout("Bearer " + result.token());
        assertThrows(UnauthorizedException.class, () -> auth.authenticate("Bearer " + result.token()));
    }

    @Test
    void expiresSessionsAndPersistsValidatedSettings() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryUserRepository users = new InMemoryUserRepository();
        InMemoryUserSettingsRepository settings = new InMemoryUserSettingsRepository();
        InMemoryAuthSessionRepository sessions = new InMemoryAuthSessionRepository();
        AuthApplicationService auth = service(users, settings, sessions, clock, Duration.ofHours(1));

        AuthApplicationService.AuthResult result = auth.register("alice", "correct horse battery staple");
        UserSettings updated = auth.updateSettings(result.user(), "dark", "en-US", "https://models.example/v1", "demo-model", 30, 900, 120_000);
        assertEquals("dark", updated.theme());
        assertEquals("en-US", updated.locale());
        assertEquals(1, updated.version());
        assertEquals(updated, auth.getSettings(result.user()));

        clock.advance(Duration.ofHours(1));
        assertThrows(UnauthorizedException.class, () -> auth.authenticate("Bearer " + result.token()));
        assertTrue(sessions.findByTokenHash(sha256(result.token())).isEmpty());
    }

    private AuthApplicationService service(InMemoryUserRepository users,
                                           InMemoryUserSettingsRepository settings,
                                           InMemoryAuthSessionRepository sessions,
                                           MutableClock clock,
                                           Duration duration) {
        return new AuthApplicationService(users, settings, sessions, new Pbkdf2PasswordHasher(),
                clock, duration, new java.security.SecureRandom());
    }

    private String sha256(String token) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static final class MutableClock implements com.offcanon.port.ClockPort {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public Instant now() {
            return current;
        }

        private void advance(Duration amount) {
            current = current.plus(amount);
        }
    }
}
