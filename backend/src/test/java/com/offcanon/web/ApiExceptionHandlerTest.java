package com.offcanon.web;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
