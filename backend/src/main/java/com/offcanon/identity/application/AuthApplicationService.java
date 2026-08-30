package com.offcanon.identity.application;

import com.offcanon.identity.domain.AuthSession;
import com.offcanon.identity.domain.User;
import com.offcanon.identity.domain.UserSettings;
import com.offcanon.port.AuthSessionRepository;
import com.offcanon.port.ClockPort;
import com.offcanon.port.UserRepository;
import com.offcanon.port.UserSettingsRepository;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.shared.domain.ModelEndpointPolicy;
import com.offcanon.shared.domain.RuntimeSettingsPolicy;
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
    private final String configuredModelBaseUrl;
    private final String configuredModelName;
    private final String configuredModelAllowedBaseUrls;
    private final RuntimeSettingsPolicy runtimePolicy;

    @Autowired
    public AuthApplicationService(UserRepository users,
                                  UserSettingsRepository settings,
                                  AuthSessionRepository sessions,
                                   PasswordHasher passwords,
                                   ClockPort clock,
                                   @Value("${offcanon.auth.session-duration-hours:168}") long sessionDurationHours,
                                   @Value("${offcanon.model.base-url:}") String configuredModelBaseUrl,
                                   @Value("${offcanon.model.name:}") String configuredModelName,
                                   @Value("${offcanon.model.allowed-base-urls:}") String configuredModelAllowedBaseUrls,
                                   @Value("${offcanon.agent.max-steps:20}") int defaultMaxSteps,
                                   @Value("${offcanon.agent.run-timeout-seconds:600}") long defaultRunTimeoutSeconds,
                                   @Value("${offcanon.agent.context-limit-chars:80000}") int defaultContextLimitChars,
                                   @Value("${offcanon.agent.max-steps-ceiling:100}") int maxStepsCeiling,
                                   @Value("${offcanon.agent.run-timeout-seconds-ceiling:86400}") long runTimeoutSecondsCeiling,
                                   @Value("${offcanon.agent.context-limit-chars-ceiling:1000000}") int contextLimitCharsCeiling) {
        this(users, settings, sessions, passwords, clock,
                Duration.ofHours(Math.max(1, sessionDurationHours)), new SecureRandom(),
                configuredModelBaseUrl, configuredModelName, configuredModelAllowedBaseUrls,
                new RuntimeSettingsPolicy(defaultMaxSteps, defaultRunTimeoutSeconds, defaultContextLimitChars,
                        maxStepsCeiling, runTimeoutSecondsCeiling, contextLimitCharsCeiling));
    }

    public AuthApplicationService(UserRepository users,
                                  UserSettingsRepository settings,
                                  AuthSessionRepository sessions,
                                  PasswordHasher passwords,
                                  ClockPort clock,
                                  Duration sessionDuration,
                                  SecureRandom random) {
        this(users, settings, sessions, passwords, clock, sessionDuration, random, "", "", "",
                RuntimeSettingsPolicy.defaults());
    }

    /** Constructor with explicit model endpoint trust configuration for tests and embedded runtimes. */
    public AuthApplicationService(UserRepository users,
                                  UserSettingsRepository settings,
                                  AuthSessionRepository sessions,
                                  PasswordHasher passwords,
                                  ClockPort clock,
                                  Duration sessionDuration,
                                  SecureRandom random,
                                  String configuredModelBaseUrl,
                                  String configuredModelAllowedBaseUrls) {
        this(users, settings, sessions, passwords, clock, sessionDuration, random,
                configuredModelBaseUrl, "", configuredModelAllowedBaseUrls, RuntimeSettingsPolicy.defaults());
    }

    /** Constructor with explicit model defaults and endpoint trust configuration for tests and embedded runtimes. */
    public AuthApplicationService(UserRepository users,
                                  UserSettingsRepository settings,
                                  AuthSessionRepository sessions,
                                  PasswordHasher passwords,
                                  ClockPort clock,
                                  Duration sessionDuration,
                                  SecureRandom random,
                                  String configuredModelBaseUrl,
                                  String configuredModelName,
                                  String configuredModelAllowedBaseUrls) {
        this(users, settings, sessions, passwords, clock, sessionDuration, random,
                configuredModelBaseUrl, configuredModelName, configuredModelAllowedBaseUrls,
                RuntimeSettingsPolicy.defaults());
    }

    /** Constructor with explicit runtime defaults and deployment ceilings. */
    public AuthApplicationService(UserRepository users,
                                  UserSettingsRepository settings,
                                  AuthSessionRepository sessions,
                                  PasswordHasher passwords,
                                  ClockPort clock,
                                  Duration sessionDuration,
                                  SecureRandom random,
                                  String configuredModelBaseUrl,
                                  String configuredModelName,
                                  String configuredModelAllowedBaseUrls,
                                  RuntimeSettingsPolicy runtimePolicy) {
        this.users = Objects.requireNonNull(users, "users");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.passwords = Objects.requireNonNull(passwords, "passwords");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sessionDuration = Objects.requireNonNull(sessionDuration, "sessionDuration");
        this.random = Objects.requireNonNull(random, "random");
        this.configuredModelBaseUrl = configuredModelBaseUrl == null ? "" : configuredModelBaseUrl.trim();
        this.configuredModelName = configuredModelName == null ? "" : configuredModelName.trim();
        this.configuredModelAllowedBaseUrls = configuredModelAllowedBaseUrls == null
                ? "" : configuredModelAllowedBaseUrls.trim();
        this.runtimePolicy = Objects.requireNonNull(runtimePolicy, "runtimePolicy");
        // Validate deployment configuration at startup/constructor time rather
        // than allowing a malformed allowlist to fail after a user saves it.
        ModelEndpointPolicy.allowedEndpoints(this.configuredModelBaseUrl,
                this.configuredModelAllowedBaseUrls, environmentModelBaseUrl());
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
        settings.save(UserSettings.defaults(user.id(), clock.now(), runtimePolicy));
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
        return settings.findByUserId(user.id()).orElseGet(() -> settings.save(UserSettings.defaults(user.id(), clock.now(), runtimePolicy)));
    }

    public UserSettings updateSettings(User user,
                                       String theme,
                                       String locale,
                                       String modelEndpoint,
                                       String modelName,
                                       int agentMaxSteps,
                                       long agentRunTimeoutSeconds,
                                       int contextLimitChars) {
        String normalizedEndpoint = modelEndpointOrEmpty(modelEndpoint);
        if (!normalizedEndpoint.isBlank()
                && !ModelEndpointPolicy.isAllowed(normalizedEndpoint,
                configuredModelBaseUrl, configuredModelAllowedBaseUrls, environmentModelBaseUrl())) {
            throw new DomainException("MODEL_ENDPOINT_NOT_ALLOWED",
                    "The selected model endpoint is not in the server's trusted endpoint allowlist");
        }
        runtimePolicy.validate(agentMaxSteps, agentRunTimeoutSeconds, contextLimitChars);
        UserSettings current = getSettings(user);
        return settings.save(current.updated(theme, locale, modelEndpoint, modelName,
                agentMaxSteps, agentRunTimeoutSeconds, contextLimitChars, clock.now()));
    }

    /**
     * Returns non-secret model configuration information for the settings UI.
     * The API key itself is deliberately represented only as a boolean and is
     * never returned, persisted, or included in an exception message.
     */
    public ModelConfigurationStatus modelConfigurationStatus(User user) {
        UserSettings current = getSettings(user);
        String environmentEndpoint = environmentModelBaseUrl();
        String environmentModel = environmentModelName();
        String defaultEndpoint = firstNonBlank(configuredModelBaseUrl, environmentEndpoint);
        String defaultModel = firstNonBlank(configuredModelName, environmentModel);
        String selectedEndpoint = firstNonBlank(current.modelEndpoint(), defaultEndpoint);
        String selectedModel = firstNonBlank(current.modelName(), defaultModel);
        boolean selectedEndpointAllowed = selectedEndpoint != null
                && ModelEndpointPolicy.isAllowed(selectedEndpoint, configuredModelBaseUrl,
                configuredModelAllowedBaseUrls, environmentEndpoint);
        int allowedEndpointCount = ModelEndpointPolicy.allowedEndpoints(configuredModelBaseUrl,
                configuredModelAllowedBaseUrls, environmentEndpoint).size();
        return new ModelConfigurationStatus(
                hasEnvironmentApiKey(),
                defaultEndpoint != null,
                defaultModel != null,
                selectedEndpoint != null,
                selectedModel != null,
                selectedEndpointAllowed,
                selectedEndpoint,
                selectedModel,
                allowedEndpointCount,
                ModelEndpointPolicy.allowedEndpoints(configuredModelBaseUrl,
                        configuredModelAllowedBaseUrls, environmentEndpoint).stream().toList());
    }

    /** Validates a user-selected endpoint without changing persisted settings. */
    public void validateModelEndpoint(User user, String endpoint) {
        String normalized = modelEndpointOrEmpty(endpoint);
        if (normalized.isBlank()) return;
        if (!ModelEndpointPolicy.isAllowed(normalized, configuredModelBaseUrl,
                configuredModelAllowedBaseUrls, environmentModelBaseUrl())) {
            throw new DomainException("MODEL_ENDPOINT_NOT_ALLOWED",
                    "The selected model endpoint is not in the server's trusted endpoint allowlist");
        }
    }

    public RuntimeSettingsPolicy runtimePolicy() {
        return runtimePolicy;
    }

    private boolean hasEnvironmentApiKey() {
        String value = System.getenv("OFFCANON_MODEL_API_KEY");
        return value != null && !value.isBlank();
    }

    private String environmentModelName() {
        String value = System.getenv("OFFCANON_MODEL_NAME");
        return value == null ? "" : value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private String modelEndpointOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String environmentModelBaseUrl() {
        String offcanon = System.getenv("OFFCANON_MODEL_BASE_URL");
        return offcanon == null ? "" : offcanon.trim();
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

    public record ModelConfigurationStatus(boolean apiKeyConfigured,
                                           boolean defaultEndpointConfigured,
                                           boolean defaultModelConfigured,
                                           boolean effectiveEndpointConfigured,
                                           boolean effectiveModelConfigured,
                                           boolean effectiveEndpointAllowed,
                                           String effectiveEndpoint,
                                           String effectiveModel,
                                           int allowedEndpointCount,
                                           java.util.List<String> allowedEndpoints) {
    }
}
