package com.aijob.backend.auth.service;

import com.aijob.backend.common.ApiErrorCode;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.config.AppProperties;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
public class VerificationCodeService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_KEY = "career:auth:code:";
    private static final String ATTEMPT_KEY = "career:auth:code:attempts:";
    private static final String COOLDOWN_KEY = "career:auth:code:cooldown:";

    private final StringRedisTemplate redisTemplate;
    private final AppProperties properties;

    public VerificationCodeService(StringRedisTemplate redisTemplate, AppProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public enum VerifyResult {
        OK, INVALID, TOO_MANY_ATTEMPTS
    }

    public String issue(String email) {
        String normalized = normalize(email);
        try {
            Boolean cooling = redisTemplate.hasKey(COOLDOWN_KEY + normalized);
            if (Boolean.TRUE.equals(cooling)) {
                Long ttl = redisTemplate.getExpire(COOLDOWN_KEY + normalized, TimeUnit.SECONDS);
                long retryAfter = ttl == null || ttl <= 0
                        ? properties.resetCode().cooldownSeconds() : ttl;
                throw new BusinessException(ApiErrorCode.CODE_SEND_TOO_FREQUENT,
                        ApiErrorCode.CODE_SEND_TOO_FREQUENT.getDefaultMessage(), retryAfter);
            }
            String code = String.format("%06d", RANDOM.nextInt(1_000_000));
            redisTemplate.opsForValue().set(CODE_KEY + normalized, code,
                    Duration.ofSeconds(properties.resetCode().ttlSeconds()));
            redisTemplate.opsForValue().set(COOLDOWN_KEY + normalized, "1",
                    Duration.ofSeconds(properties.resetCode().cooldownSeconds()));
            redisTemplate.delete(ATTEMPT_KEY + normalized);
            return code;
        } catch (RedisConnectionFailureException ex) {
            throw new BusinessException(ApiErrorCode.DEPENDENCY_UNAVAILABLE, "验证码服务暂不可用");
        }
    }

    public VerifyResult verifyAndConsume(String email, String code) {
        String normalized = normalize(email);
        try {
            Long attempts = redisTemplate.opsForValue().increment(ATTEMPT_KEY + normalized);
            if (attempts != null && attempts == 1L) {
                redisTemplate.expire(ATTEMPT_KEY + normalized,
                        Duration.ofSeconds(properties.resetCode().ttlSeconds()));
            }
            if (attempts != null && attempts > properties.resetCode().maxAttempts()) {
                redisTemplate.delete(CODE_KEY + normalized);
                return VerifyResult.TOO_MANY_ATTEMPTS;
            }
            String stored = redisTemplate.opsForValue().get(CODE_KEY + normalized);
            if (stored == null) {
                return VerifyResult.INVALID;
            }
            if (!stored.equals(code)) {
                return VerifyResult.INVALID;
            }
            redisTemplate.delete(CODE_KEY + normalized);
            redisTemplate.delete(ATTEMPT_KEY + normalized);
            return VerifyResult.OK;
        } catch (RedisConnectionFailureException ex) {
            throw new BusinessException(ApiErrorCode.DEPENDENCY_UNAVAILABLE, "验证码服务暂不可用");
        }
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}