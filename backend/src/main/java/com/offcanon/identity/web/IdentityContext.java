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
        String header = request.getHeader("Authorization");
        if (header != null && !header.isBlank()) return header;
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if ("OFFCANON_SESSION".equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    return "Bearer " + cookie.getValue();
                }
            }
        }
        throw new UnauthorizedException("Authentication is required");
    }
}
