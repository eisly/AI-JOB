package com.aijob.backend.auth.security;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

public class OriginValidationFilter extends OncePerRequestFilter {

    private static final Set<String> GUARDED_PATHS = Set.of(
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/csrf");

    private final OriginGuard originGuard;

    public OriginValidationFilter(OriginGuard originGuard) {
        this.originGuard = originGuard;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (GUARDED_PATHS.contains(request.getServletPath())) {
            try {
                originGuard.requireTrustedOrigin(request);
            } catch (BusinessException ex) {
                writeForbidden(request, response);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void writeForbidden(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(403);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Object attribute = request.getAttribute(TraceIdFilter.TRACE_ID_ATTR);
        String traceId = attribute == null ? "" : attribute.toString();
        String body = "{\"code\":40301,\"msg\":\"请求来源不受信任\",\"data\":null,\"traceId\":\"%s\","
                + "\"error\":{\"key\":\"FORBIDDEN\",\"retryable\":false}}".formatted(traceId);
        response.getWriter().write(body);
    }
}