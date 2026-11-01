package com.aijob.backend.auth.service;

import com.aijob.backend.common.ApiErrorCode;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.config.AppProperties;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

@Service
public class RateLimitService {

    private static final String ACCOUNT_KEY = "career:rate:login:acct:";
    private static final String IP_KEY = "career:rate:login:ip:";

    private final StringRedisTemplate redisTemplate;
    private final AppProperties properties;

    public RateLimitService(StringRedisTemplate redisTemplate, AppProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public long retryAfterSeconds(String email, String clientIp) {
        try {
            long accountCount = currentCount(ACCOUNT_KEY + sha256Hex(normalize(email)));
            if (accountCount >= properties.security().loginRateLimit()) {
                return remainingTtl(ACCOUNT_KEY + sha256Hex(normalize(email)));
            }
            long ipCount = currentCount(IP_KEY + sha256Hex(clientIp == null ? "unknown" : clientIp));
            if (ipCount >= properties.security().loginRateLimit()) {
                return remainingTtl(IP_KEY + sha256Hex(clientIp == null ? "unknown" : clientIp));
            }
            return 0L;
        } catch (RedisConnectionFailureException ex) {
            throw new BusinessException(ApiErrorCode.DEPENDENCY_UNAVAILABLE, "限流服务暂不可用");
        }
    }

    public void recordFailure(String email, String clientIp) {
        try {
            increment(ACCOUNT_KEY + sha256Hex(normalize(email)));
            increment(IP_KEY + sha256Hex(clientIp == null ? "unknown" : clientIp));
        } catch (RedisConnectionFailureException ex) {
            throw new BusinessException(ApiErrorCode.DEPENDENCY_UNAVAILABLE, "限流服务暂不可用");
        }
    }

    private void increment(String key) {
        Long value = redisTemplate.opsForValue().increment(key);
        if (value != null && value == 1L) {
            redisTemplate.expire(key, Duration.ofSeconds(properties.security().loginRateWindowSeconds()));
        }
    }

    private long currentCount(String key) {
        String value = redisTemplate.opsForValue().get(key);
        return value == null ? 0L : Long.parseLong(value);
    }

    private long remainingTtl(String key) {
        Long ttl = redisTemplate.getExpire(key);
        if (ttl == null || ttl < 0) {
            return properties.security().loginRateWindowSeconds();
        }
        return ttl;
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}