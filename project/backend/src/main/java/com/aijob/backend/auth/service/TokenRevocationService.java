package com.aijob.backend.auth.service;

import com.aijob.backend.common.ApiErrorCode;
import com.aijob.backend.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class TokenRevocationService {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);
    private static final String REVOKED_KEY = "career:auth:revoked:";

    private final StringRedisTemplate redisTemplate;

    public TokenRevocationService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void revoke(String jti, long remainingSeconds) {
        if (jti == null || jti.isBlank() || remainingSeconds <= 0) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(REVOKED_KEY + jti, "1", Duration.ofSeconds(remainingSeconds));
        } catch (RedisConnectionFailureException ex) {
            throw new BusinessException(ApiErrorCode.DEPENDENCY_UNAVAILABLE, "撤销服务暂不可用，退出未完成");
        }
    }

    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(REVOKED_KEY + jti));
        } catch (RedisConnectionFailureException ex) {
            log.warn("redis unavailable while checking revocation, allowing request through, traceId lookup degraded");
            return false;
        }
    }
}