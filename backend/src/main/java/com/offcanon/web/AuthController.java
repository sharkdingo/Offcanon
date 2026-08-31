package com.offcanon.web;

import com.offcanon.identity.application.AuthApplicationService;
import com.offcanon.identity.domain.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthApplicationService auth;

    public AuthController(AuthApplicationService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody CredentialsRequest request,
                                 HttpServletRequest servletRequest,
                                 HttpServletResponse servletResponse) {
        AuthApplicationService.AuthResult result = auth.register(request.username(), request.password());
        addSessionCookie(servletRequest, servletResponse, result);
        return response(result);
    }

    /** Package/test convenience overload for callers without an HTTP request. */
    AuthResponse register(CredentialsRequest request, HttpServletResponse servletResponse) {
        return register(request, null, servletResponse);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody CredentialsRequest request,
                              HttpServletRequest servletRequest,
                              HttpServletResponse servletResponse) {
        AuthApplicationService.AuthResult result = auth.login(request.username(), request.password());
        addSessionCookie(servletRequest, servletResponse, result);
        return response(result);
    }

    /** Package/test convenience overload for callers without an HTTP request. */
    AuthResponse login(CredentialsRequest request, HttpServletResponse servletResponse) {
        return login(request, null, servletResponse);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse servletResponse) {
        try {
            // Logout is intentionally idempotent from the browser's point of
            // view. An expired/revoked cookie must still be removed locally,
            // rather than turning a harmless sign-out click into a 401.
            try {
                auth.logout(cookieAuthorizationOrNull(request));
            } catch (com.offcanon.shared.web.UnauthorizedException ignored) {
                // The session is already invalid; clearing the browser cookie
                // is the only useful outcome.
            }
        } finally {
            clearSessionCookie(request, servletResponse);
        }
    }

    @GetMapping("/me")
    public UserResponse me(HttpServletRequest request) {
        return toUser(auth.authenticate(authorization(request)));
    }

    @PutMapping("/password")
    public AuthResponse changePassword(@Valid @RequestBody ChangePasswordRequest request,
                               HttpServletRequest servletRequest,
                               HttpServletResponse servletResponse) {
        AuthApplicationService.AuthResult result = auth.changePassword(auth.authenticate(cookieAuthorization(servletRequest)),
                request.currentPassword(), request.newPassword());
        addSessionCookie(servletRequest, servletResponse, result);
        return response(result);
    }

    private AuthResponse response(AuthApplicationService.AuthResult result) {
        return new AuthResponse(result.expiresAt(), toUser(result.user()));
    }

    private UserResponse toUser(User user) {
        return new UserResponse(user.id(), user.username(), user.createdAt());
    }

    private void addSessionCookie(HttpServletRequest request,
                                  HttpServletResponse response,
                                  AuthApplicationService.AuthResult result) {
        long maxAge = Math.max(1, java.time.Duration.between(java.time.Instant.now(), result.expiresAt()).toSeconds());
        response.addHeader("Set-Cookie", "OFFCANON_SESSION=" + result.token()
                + "; Max-Age=" + maxAge + "; Path=/; HttpOnly; SameSite=Strict"
                + (request != null && request.isSecure() ? "; Secure" : ""));
    }

    private String cookieAuthorization(HttpServletRequest request) {
        String token = null;
        boolean found = false;
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if ("OFFCANON_SESSION".equals(cookie.getName())) {
                    if (found) {
                        throw new com.offcanon.shared.web.UnauthorizedException("Session is invalid or expired");
                    }
                    found = true;
                    if (cookie.getValue() != null && !cookie.getValue().isBlank()) {
                        token = cookie.getValue();
                    }
                }
            }
        }
        if (token != null) return "Bearer " + token;
        throw new com.offcanon.shared.web.UnauthorizedException("Authentication is required");
    }

    private String authorization(HttpServletRequest request) {
        return cookieAuthorization(request);
    }

    private String cookieAuthorizationOrNull(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        String token = null;
        boolean found = false;
        for (var cookie : request.getCookies()) {
            if ("OFFCANON_SESSION".equals(cookie.getName())) {
                if (found) return null;
                found = true;
                if (cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    token = cookie.getValue();
                }
            }
        }
        return token == null ? null : "Bearer " + token;
    }

    private void clearSessionCookie(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader("Set-Cookie", "OFFCANON_SESSION=; Max-Age=0; Path=/; HttpOnly; SameSite=Strict"
                + (request != null && request.isSecure() ? "; Secure" : ""));
    }

    public record CredentialsRequest(@NotBlank @Size(min = 3, max = 64) String username,
                                     @NotBlank @Size(min = 8, max = 256) String password) {
    }

    public record ChangePasswordRequest(@NotBlank @Size(min = 8, max = 256) String currentPassword,
                                        @NotBlank @Size(min = 8, max = 256) String newPassword) {
    }

    public record AuthResponse(java.time.Instant expiresAt, UserResponse user) {
    }

    public record UserResponse(java.util.UUID id, String username, java.time.Instant createdAt) {
    }
}
