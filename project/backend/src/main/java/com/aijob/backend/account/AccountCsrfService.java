package com.aijob.backend.account;

import com.aijob.backend.common.ApiErrorCode;
import com.aijob.backend.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * 账号写接口的独立 CSRF 令牌：绑定 userId + jti，与找回密码/刷新会话的 CSRF 机制隔离。
 */
@Service
public class AccountCsrfService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY_PREFIX = "career:account:csrf:";

    private final StringRedisTemplate redisTemplate;
    private final long ttlSeconds;

    public AccountCsrfService(StringRedisTemplate redisTemplate,
                              @Value("${app.jwt.access-ttl-seconds:900}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(String userId, String jti) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
            redisTemplate.opsForValue().set(key(userId, jti), token, Duration.ofSeconds(ttlSeconds));
        } catch (RedisConnectionFailureException ex) {
            throw new BusinessException(ApiErrorCode.DEPENDENCY_UNAVAILABLE, "安全令牌服务暂不可用");
        }
        return token;
    }

    public void require(String userId, String jti, String token) {
        if (token == null || token.isBlank()) {
            throw AccountExceptionFactory.csrfInvalid();
        }
        String expected;
        try {
            expected = redisTemplate.opsForValue().get(key(userId, jti));
        } catch (RedisConnectionFailureException ex) {
            throw new BusinessException(ApiErrorCode.DEPENDENCY_UNAVAILABLE, "安全令牌服务暂不可用");
        }
        if (expected == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw AccountExceptionFactory.csrfInvalid();
        }
    }

    private static String key(String userId, String jti) {
        return KEY_PREFIX + userId + ":" + jti;
    }
}