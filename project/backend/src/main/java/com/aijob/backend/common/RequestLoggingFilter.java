package com.aijob.backend.common;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 记录每一次 HTTP 交互的入口、出口与耗时。
 * 仅输出方法、路径、客户端、认证标记、状态码与耗时；
 * 不记录请求体、查询参数值或 Authorization 内容，避免泄露简历、JD 与凭证。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        String method = request.getMethod();
        String path = request.getRequestURI();
        log.info("--> {} {} client={} auth={}", method, path, clientAddress(request),
                hasAuthorization(request) ? "yes" : "no");
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException ex) {
            log.error("!! {} {} failed duration={}ms error={}", method, path, elapsedMillis(started),
                    ex.getClass().getSimpleName());
            throw ex;
        }
        long elapsed = elapsedMillis(started);
        int status = response.getStatus();
        if (status >= 500) {
            log.error("<-- {} {} status={} duration={}ms", method, path, status, elapsed);
        } else if (status >= 400) {
            log.warn("<-- {} {} status={} duration={}ms", method, path, status, elapsed);
        } else {
            log.info("<-- {} {} status={} duration={}ms", method, path, status, elapsed);
        }
    }

    private static boolean hasAuthorization(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        return authorization != null && !authorization.isBlank();
    }

    private static String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}