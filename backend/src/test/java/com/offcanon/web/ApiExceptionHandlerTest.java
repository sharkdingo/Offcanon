package com.offcanon.web;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.http.HttpMethod;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiExceptionHandlerTest {
    @Test
    void ignoresIoFailureOnlyAfterAStreamingResponseWasCommitted() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(true);

        assertNull(new ApiExceptionHandler().io(new IOException("client disconnected"), response));
    }

    @Test
    void reportsIoFailureBeforeTheResponseWasCommitted() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(false);

        var problem = new ApiExceptionHandler().io(new IOException("read failed"), response);

        assertNotNull(problem);
        assertEquals(500, problem.getStatus());
        assertEquals("INTERNAL_ERROR", problem.getProperties().get("code"));
    }

    @Test
    void mapsMissingStaticResourcesToNotFound() throws Exception {
        var problem = new ApiExceptionHandler().resourceNotFound(
                new NoResourceFoundException(HttpMethod.GET, "missing"));

        assertNotNull(problem);
        assertEquals(404, problem.getStatus());
        assertEquals("NOT_FOUND", problem.getProperties().get("code"));
    }

    @Test
    void mapsUnsupportedMethodsToMethodNotAllowedInsteadOfInternalError() {
        var problem = new ApiExceptionHandler().methodNotAllowed(
                new HttpRequestMethodNotSupportedException("GET", java.util.List.of("POST")));

        assertNotNull(problem);
        assertEquals(405, problem.getStatus());
        assertEquals("METHOD_NOT_ALLOWED", problem.getProperties().get("code"));
    }

    @Test
    void mapsMalformedRequestsToBadRequest() {
        var problem = new ApiExceptionHandler().malformedRequest(
                new org.springframework.http.converter.HttpMessageNotReadableException("bad body"));

        assertNotNull(problem);
        assertEquals(400, problem.getStatus());
        assertEquals("MALFORMED_REQUEST", problem.getProperties().get("code"));
    }

    @Test
    void redactsSecretsAndBoundsDiagnosticDetails() {
        var problem = new ApiExceptionHandler().domain(
                new com.offcanon.shared.domain.DomainException("MODEL_REQUEST_FAILED",
                        "authorization: Bearer super-secret\n" + "x".repeat(700)));

        String detail = problem.getDetail();
        assertTrue(!detail.contains("super-secret"));
        assertTrue(detail.length() <= 503);
        assertTrue(!detail.contains("\n"));
    }

    @Test
    void redactsQuotedJsonSecretsInDiagnosticDetails() {
        var problem = new ApiExceptionHandler().domain(
                new com.offcanon.shared.domain.DomainException("MODEL_REQUEST_FAILED",
                        "{\"api_key\":\"json-secret\",\"authorization\":\"Bearer json-token\"}"));

        String detail = problem.getDetail();
        assertTrue(!detail.contains("json-secret"));
        assertTrue(!detail.contains("json-token"));
        assertTrue(detail.contains("[REDACTED]"));
    }
}
