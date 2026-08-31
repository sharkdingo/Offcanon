package com.offcanon.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApiResponseCacheControlFilterTest {
    @Test
    void marksAccountScopedApiResponsesAsNonCacheable() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/settings/export");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ApiResponseCacheControlFilter().doFilter(request, response, new MockFilterChain());

        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("no-cache", response.getHeader("Pragma"));
        assertEquals(0L, response.getDateHeader("Expires"));
    }

    @Test
    void leavesStaticResponsesCachePolicyUntouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/assets/app.js");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ApiResponseCacheControlFilter().doFilter(request, response, new MockFilterChain());

        assertNull(response.getHeader("Cache-Control"));
        assertNull(response.getHeader("Pragma"));
    }

    @Test
    void handlesAConfiguredServletContextPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/offcanon/api/settings");
        request.setContextPath("/offcanon");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ApiResponseCacheControlFilter().doFilter(request, response, new MockFilterChain());

        assertEquals("no-store", response.getHeader("Cache-Control"));
    }
}
