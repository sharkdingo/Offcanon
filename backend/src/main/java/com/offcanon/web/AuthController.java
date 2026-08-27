package com.offcanon.web;

import com.offcanon.identity.application.AuthApplicationService;
import com.offcanon.identity.domain.User;
import com.offcanon.identity.domain.UserSettings;
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
    public AuthResponse register(@Valid @RequestBody CredentialsRequest request, HttpServletResponse servletResponse) {
        AuthApplicationService.AuthResult result = auth.register(request.username(), request.password());
        addSessionCookie(servletResponse, result);
        return response(result);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody CredentialsRequest request, HttpServletResponse servletResponse) {
        AuthApplicationService.AuthResult result = auth.login(request.username(), request.password());
        addSessionCookie(servletResponse, result);
        return response(result);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse servletResponse) {
        auth.logout(authorization(request));
        servletResponse.addHeader("Set-Cookie", "OFFCANON_SESSION=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax");
    }

    @GetMapping("/me")
    public UserResponse me(HttpServletRequest request) {
        return toUser(auth.authenticate(authorization(request)));
    }

    private AuthResponse response(AuthApplicationService.AuthResult result) {
        return new AuthResponse(result.token(), result.expiresAt(), toUser(result.user()));
    }

    private UserResponse toUser(User user) {
        return new UserResponse(user.id(), user.username(), user.createdAt());
    }

    private void addSessionCookie(HttpServletResponse response, AuthApplicationService.AuthResult result) {
        long maxAge = Math.max(1, java.time.Duration.between(java.time.Instant.now(), result.expiresAt()).toSeconds());
        response.addHeader("Set-Cookie", "OFFCANON_SESSION=" + result.token()
                + "; Max-Age=" + maxAge + "; Path=/; HttpOnly; SameSite=Lax");
    }

    private String authorization(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && !header.isBlank()) return header;
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if ("OFFCANON_SESSION".equals(cookie.getName())) return "Bearer " + cookie.getValue();
            }
        }
        return null;
    }

    public record CredentialsRequest(@NotBlank @Size(min = 3, max = 64) String username,
                                     @NotBlank @Size(min = 8, max = 256) String password) {
    }

    public record AuthResponse(String token, java.time.Instant expiresAt, UserResponse user) {
    }

    public record UserResponse(java.util.UUID id, String username, java.time.Instant createdAt) {
    }
}
