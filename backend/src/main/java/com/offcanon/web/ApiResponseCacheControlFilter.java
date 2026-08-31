package com.offcanon.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * API responses are account- and session-scoped.  Prevent browsers and
 * intermediaries from reusing a response after a local user signs out or
 * another account signs in on the same origin.
 */
@Component
public final class ApiResponseCacheControlFilter extends OncePerRequestFilter {
    private static final String API_PREFIX = "/api/";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isApiRequest(request)) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
            response.setDateHeader("Expires", 0);
        }
        filterChain.doFilter(request, response);
    }

    private boolean isApiRequest(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (requestUri == null) return false;
        if (contextPath != null && !contextPath.isEmpty()
                && (requestUri.equals(contextPath) || requestUri.startsWith(contextPath + "/"))) {
            requestUri = requestUri.substring(contextPath.length());
        }
        return requestUri.equals("/api") || requestUri.startsWith(API_PREFIX);
    }
}
