package com.offcanon.identity.web;

import com.offcanon.identity.application.AuthApplicationService;
import com.offcanon.identity.domain.User;
import com.offcanon.shared.web.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Resolves the authenticated user for HTTP requests. */
@Component
public class IdentityContext {
    private final AuthApplicationService auth;

    public IdentityContext(AuthApplicationService auth) {
        this.auth = auth;
    }

    public UUID ownerId(HttpServletRequest request) {
        String authorization = authorization(request);
        return auth.authenticate(authorization).id();
    }

    public User requireUser(HttpServletRequest request) {
        return auth.authenticate(authorization(request));
    }

    private String authorization(HttpServletRequest request) {
        String token = null;
        boolean found = false;
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if ("OFFCANON_SESSION".equals(cookie.getName())) {
                    // Duplicate cookie names are ambiguous (for example when
                    // a stale path-scoped cookie shadows the current one).
                    // Reject them instead of trusting whichever order the
                    // servlet container happens to expose.
                    if (found) {
                        throw new UnauthorizedException("Session is invalid or expired");
                    }
                    found = true;
                    if (cookie.getValue() != null && !cookie.getValue().isBlank()) {
                        token = cookie.getValue();
                    }
                }
            }
        }
        if (token != null) return "Bearer " + token;
        throw new UnauthorizedException("Authentication is required");
    }
}
