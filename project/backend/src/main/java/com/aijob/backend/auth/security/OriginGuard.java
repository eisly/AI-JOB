package com.aijob.backend.auth.security;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Component;

import com.aijob.backend.common.ApiErrorCode;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.config.AppProperties;

import jakarta.servlet.http.HttpServletRequest;

@Component
public class OriginGuard {

    private final AppProperties properties;

    public OriginGuard(AppProperties properties) {
        this.properties = properties;
    }

    public void requireTrustedOrigin(HttpServletRequest request) {
        String origin = normalizeOrigin(request.getHeader("Origin"));
        if (origin == null) {
            origin = originOfReferer(request.getHeader("Referer"));
        }
        List<String> allowed = allowedOrigins();
        if (origin == null || !allowed.contains(origin)) {
            throw new BusinessException(ApiErrorCode.FORBIDDEN, "请求来源不受信任");
        }
    }

    private List<String> allowedOrigins() {
        String configured = properties.security().frontendOrigin();
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        return Arrays.stream(configured.split(","))
                .map(OriginGuard::normalizeOrigin)
                .filter(value -> value != null)
                .toList();
    }

    private static String originOfReferer(String referer) {
        if (referer == null || referer.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(referer.trim());
            if (uri.getScheme() == null || uri.getAuthority() == null) {
                return null;
            }
            return normalizeOrigin(uri.getScheme() + "://" + uri.getAuthority());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String normalizeOrigin(String origin) {
        if (origin == null || origin.isBlank() || "null".equalsIgnoreCase(origin.trim())) {
            return null;
        }
        String normalized = origin.trim();

        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? null : normalized.toLowerCase();
    }
}