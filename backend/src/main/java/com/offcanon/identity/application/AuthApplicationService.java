package com.offcanon.identity.application;

import com.offcanon.identity.domain.AuthSession;
import com.offcanon.identity.domain.User;
import com.offcanon.identity.domain.UserSettings;
import com.offcanon.port.AuthSessionRepository;
import com.offcanon.port.ClockPort;
import com.offcanon.port.UserRepository;
import com.offcanon.port.UserSettingsRepository;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.shared.web.UnauthorizedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

@Service
public class AuthApplicationService {
    private static final int TOKEN_BYTES = 32;
    private final UserRepository users;
    private final UserSettingsRepository settings;
    private final AuthSessionRepository sessions;
    private final PasswordHasher passwords;
    private final ClockPort clock;
    private final Duration sessionDuration;
    private final SecureRandom random;

    @Autowired
    public AuthApplicationService(UserRepository users,
                                  UserSettingsRepository settings,
                                  AuthSessionRepository sessions,
                                  PasswordHasher passwords,
                                  ClockPort clock,
                                  @Value("${offcanon.auth.session-duration-hours:168}") long sessionDurationHours) {
        this(users, settings, sessions, passwords, clock,
                Duration.ofHours(Math.max(1, sessionDurationHours)), new SecureRandom());
    }

    public AuthApplicationService(UserRepository users,
                                  UserSettingsRepository settings,
                                  AuthSessionRepository sessions,
                                  PasswordHasher passwords,
                                  ClockPort clock,
                                  Duration sessionDuration,
                                  SecureRandom random) {
        this.users = Objects.requireNonNull(users, "users");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.passwords = Objects.requireNonNull(passwords, "passwords");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sessionDuration = Objects.requireNonNull(sessionDuration, "sessionDuration");
        this.random = Objects.requireNonNull(random, "random");
        if (sessionDuration.isZero() || sessionDuration.isNegative()) throw new IllegalArgumentException("Session duration must be positive");
    }

    public AuthResult register(String username, String password) {
        String normalized = User.normalizeUsername(username);
        if (users.findByUsername(normalized).isPresent()) {
            throw new DomainException("USERNAME_TAKEN", "Username is already registered");
        }
        User user = User.create(normalized, passwords.hash(password), clock.now());
        try {
            users.save(user);
        } catch (DomainException error) {
            if ("USER_ALREADY_EXISTS".equals(error.code()) || "USERNAME_TAKEN".equals(error.code())) {
                throw new DomainException("USERNAME_TAKEN", "Username is already registered");
            }
            throw error;
        }
        settings.save(UserSettings.defaults(user.id(), clock.now()));
        return issue(user);
    }

    public AuthResult login(String username, String password) {
        User user = users.findByUsername(User.normalizeUsername(username))
                .orElseThrow(this::invalidCredentials);
        if (!passwords.matches(password, user.passwordHash())) throw invalidCredentials();
        return issue(user);
    }

    public User authenticate(String authorizationHeader) {
        return authenticateToken(bearerToken(authorizationHeader));
    }

    public User authenticateToken(String token) {
        if (token == null || token.isBlank()) throw new UnauthorizedException("Authentication is required");
        String hash = tokenHash(token);
        AuthSession session = sessions.findByTokenHash(hash).orElseThrow(() -> new UnauthorizedException("Session is invalid or expired"));
        Instant now = clock.now();
        if (session.expiredAt(now)) {
            sessions.deleteByTokenHash(hash);
            throw new UnauthorizedException("Session is invalid or expired");
        }
        return users.findById(session.userId()).orElseThrow(() -> new UnauthorizedException("Session user no longer exists"));
    }

    public void logout(String authorizationHeader) {
        String token = bearerToken(authorizationHeader);
        if (token != null) sessions.deleteByTokenHash(tokenHash(token));
    }

    public UserSettings getSettings(User user) {
        return settings.findByUserId(user.id()).orElseGet(() -> settings.save(UserSettings.defaults(user.id(), clock.now())));
    }

    public UserSettings updateSettings(User user,
                                       String theme,
                                       String locale,
                                       String modelEndpoint,
                                       String modelName,
                                       int agentMaxSteps,
                                       long agentRunTimeoutSeconds,
                                       int contextLimitChars) {
        UserSettings current = getSettings(user);
        return settings.save(current.updated(theme, locale, modelEndpoint, modelName,
                agentMaxSteps, agentRunTimeoutSeconds, contextLimitChars, clock.now()));
    }

    private AuthResult issue(User user) {
        sessions.deleteExpired(clock.now());
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant now = clock.now();
        Instant expires = now.plus(sessionDuration);
        sessions.save(new AuthSession(tokenHash(token), user.id(), now, expires));
        return new AuthResult(user, token, expires);
    }

    private String bearerToken(String value) {
        if (value == null || value.isBlank()) return null;
        if (!value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new UnauthorizedException("Use an Authorization: Bearer <token> header");
        }
        String token = value.substring(7).trim();
        if (token.isBlank() || token.length() > 256) throw new UnauthorizedException("Session is invalid or expired");
        return token;
    }

    private String tokenHash(String token) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private UnauthorizedException invalidCredentials() {
        return new UnauthorizedException("Invalid username or password");
    }

    public record AuthResult(User user, String token, Instant expiresAt) {
    }
}
