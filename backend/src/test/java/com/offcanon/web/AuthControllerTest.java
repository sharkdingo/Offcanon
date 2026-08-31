package com.offcanon.web;

import com.offcanon.identity.application.AuthApplicationService;
import com.offcanon.identity.domain.User;
import com.offcanon.shared.web.UnauthorizedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void authResponsesNeverSerializeTheSessionTokenAndSetHardenedCookieAttributes() throws Exception {
        AuthApplicationService auth = mock(AuthApplicationService.class);
        User user = User.create("alice", "hash", Instant.parse("2026-08-31T00:00:00Z"));
        String token = "opaque-session-token";
        when(auth.login("alice", "password")).thenReturn(
                new AuthApplicationService.AuthResult(user, token,
                        Instant.now().plusSeconds(3600)));

        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthController.AuthResponse body = new AuthController(auth)
                .login(new AuthController.CredentialsRequest("alice", "password"), response);

        String json = mapper.writeValueAsString(body);
        assertFalse(json.contains(token));
        assertNotNull(response.getHeader("Set-Cookie"));
        String cookie = response.getHeader("Set-Cookie");
        assertTrue(cookie.startsWith("OFFCANON_SESSION="));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Strict"));
        assertTrue(cookie.contains("Path=/"));
    }

    @Test
    void logoutClearsAnExpiredOrMalformedCookieAndRemainsIdempotent() {
        AuthApplicationService auth = mock(AuthApplicationService.class);
        doThrow(new UnauthorizedException("expired"))
                .when(auth).logout(anyString());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie("OFFCANON_SESSION", "expired-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        new AuthController(auth).logout(request, response);

        verify(auth).logout("Bearer expired-token");
        assertTrue(response.getHeader("Set-Cookie").contains("Max-Age=0"));
        assertTrue(response.getHeader("Set-Cookie").contains("HttpOnly"));
    }

    @Test
    void logoutWithoutCookieStillClearsTheBrowserState() {
        AuthApplicationService auth = mock(AuthApplicationService.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new AuthController(auth).logout(request, response);

        verify(auth).logout(null);
        assertTrue(response.getHeader("Set-Cookie").contains("Max-Age=0"));
    }
}
