package com.aijob.backend.auth.service;

import com.aijob.backend.auth.entity.AuthSession;
import com.aijob.backend.auth.mapper.AuthSessionMapper;
import com.aijob.backend.common.ApiErrorCode;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.config.AppProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class SessionService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthSessionMapper authSessionMapper;
    private final AppProperties properties;

    public SessionService(AuthSessionMapper authSessionMapper, AppProperties properties) {
        this.authSessionMapper = authSessionMapper;
        this.properties = properties;
    }

    public record IssuedSession(String refreshToken, String csrfToken, Instant expiresAt) {
    }

    public record RotatedSession(String refreshToken, String csrfToken, String userId, Instant expiresAt) {
    }

    @Transactional
    public IssuedSession issue(String userId) {
        String refreshToken = randomToken();
        String csrfToken = randomToken();
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(properties.session().refreshTtlSeconds());
        AuthSession session = new AuthSession();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setFamilyId(UUID.randomUUID().toString());
        session.setTokenHash(sha256(refreshToken));
        session.setCsrfHash(sha256(csrfToken));
        session.setParentId(null);
        session.setStatus("ACTIVE");
        session.setExpiresAt(expiresAt);
        authSessionMapper.insert(session);
        return new IssuedSession(refreshToken, csrfToken, expiresAt);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public RotatedSession rotate(String rawRefreshToken, String csrfToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new BusinessException(ApiErrorCode.UNAUTHENTICATED, "刷新会话不存在");
        }
        Instant now = Instant.now();
        AuthSession current = authSessionMapper.selectByTokenHashForUpdate(sha256(rawRefreshToken));
        if (current == null) {
            throw new BusinessException(ApiErrorCode.UNAUTHENTICATED, "刷新会话不存在");
        }
        if (!"ACTIVE".equals(current.getStatus()) || current.getExpiresAt().isBefore(now)) {
            if ("ROTATED".equals(current.getStatus()) || "REVOKED".equals(current.getStatus())) {
                authSessionMapper.revokeFamily(current.getFamilyId(), now);
            }
            throw new BusinessException(ApiErrorCode.UNAUTHENTICATED, "刷新会话已失效，请重新登录");
        }
        if (csrfToken == null || csrfToken.isBlank()
                || !MessageDigest.isEqual(sha256(csrfToken), current.getCsrfHash())) {
            throw new BusinessException(ApiErrorCode.UNAUTHENTICATED, "会话校验失败，请重新登录");
        }
        authSessionMapper.markRotated(current.getId(), now);
        String newRefreshToken = randomToken();
        String newCsrfToken = randomToken();
        Instant expiresAt = now.plusSeconds(properties.session().refreshTtlSeconds());
        AuthSession next = new AuthSession();
        next.setId(UUID.randomUUID().toString());
        next.setUserId(current.getUserId());
        next.setFamilyId(current.getFamilyId());
        next.setTokenHash(sha256(newRefreshToken));
        next.setCsrfHash(sha256(newCsrfToken));
        next.setParentId(current.getId());
        next.setStatus("ACTIVE");
        next.setExpiresAt(expiresAt);
        authSessionMapper.insert(next);
        return new RotatedSession(newRefreshToken, newCsrfToken, current.getUserId(), expiresAt);
    }

    @Transactional
    public void revoke(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        AuthSession current = authSessionMapper.selectByTokenHashForUpdate(sha256(rawRefreshToken));
        if (current != null) {
            authSessionMapper.revokeFamily(current.getFamilyId(), now);
        }
    }

    public AuthSession findActive(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return null;
        }
        AuthSession session = authSessionMapper.selectByTokenHash(sha256(rawRefreshToken));
        if (session == null || !"ACTIVE".equals(session.getStatus()) || session.getExpiresAt().isBefore(Instant.now())) {
            return null;
        }
        return session;
    }

    @Transactional
    public void revokeAll(String userId) {
        authSessionMapper.revokeAllByUser(userId, Instant.now());
    }

    @Transactional
    public String rotateCsrf(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new BusinessException(ApiErrorCode.UNAUTHENTICATED, "无有效会话");
        }
        AuthSession current = authSessionMapper.selectByTokenHashForUpdate(sha256(rawRefreshToken));
        if (current == null || !"ACTIVE".equals(current.getStatus())
                || current.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException(ApiErrorCode.UNAUTHENTICATED, "无有效会话");
        }
        String csrfToken = randomToken();
        authSessionMapper.updateCsrfHash(current.getId(), sha256(csrfToken), Instant.now());
        return csrfToken;
    }

    public void requireCsrf(String rawRefreshToken, String csrfToken) {
        if (csrfToken == null || csrfToken.isBlank()) {
            throw new BusinessException(ApiErrorCode.UNAUTHENTICATED, "缺少 CSRF 令牌");
        }
        AuthSession current = findActive(rawRefreshToken);
        if (current == null || !MessageDigest.isEqual(sha256(csrfToken), current.getCsrfHash())) {
            throw new BusinessException(ApiErrorCode.UNAUTHENTICATED, "会话校验失败，请重新登录");
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}