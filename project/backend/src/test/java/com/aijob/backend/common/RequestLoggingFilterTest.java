package com.aijob.backend.common;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.ServletException;

class RequestLoggingFilterTest {

    @Test
    void passesThroughAndRecordsStatus() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/jobs");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        new RequestLoggingFilter().doFilter(request, response, (req, res) -> {
            invoked.set(true);
            ((MockHttpServletResponse) res).setStatus(200);
        });

        assertTrue(invoked.get());
    }

    @Test
    void rethrowsServletExceptionFromChain() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/resumes");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(ServletException.class, () -> new RequestLoggingFilter().doFilter(request, response,
                (req, res) -> {
                    throw new ServletException("boom");
                }));
    }
}