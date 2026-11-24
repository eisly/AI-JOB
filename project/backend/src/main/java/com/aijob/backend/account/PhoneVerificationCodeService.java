package com.aijob.backend.account;

import com.aijob.backend.common.ApiErrorCode;
import com.aijob.backend.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 手机号验证码：6 位数字 / 10 分钟有效 / 60 秒冷却 / 最多 5 次尝试，按用途与号码隔离。
 * 键前缀 career:account:sms:*，与找回密码的 career:auth:code:* 完全隔离。
 */
@Service
public class PhoneVerificationCodeService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_PREFIX = "career:account:sms:code:";
    private static final String ATTEMPT_PREFIX = "career:account:sms:attempts:";
    private static final String COOLDOWN_PREFIX = "career:account:sms:cooldown:";

    private final StringRedisTemplate redisTemplate;
    private final SmsSender smsSender;
    private final long ttlSeconds;
    private final int maxAttempts;
    private final long cooldownSeconds;

    public PhoneVerificationCodeService(StringRedisTemplate redisTemplate,
                                        SmsSender smsSender,
                                        @Value("${app.account.sms-code.ttl-seconds:600}") long ttlSeconds,
                                        @Value("${app.account.sms-code.max-attempts:5}") int maxAttempts,
                                        @Value("${app.account.sms-code.cooldown-seconds:60}") long cooldownSeconds) {
        this.redisTemplate = redisTemplate;
        this.smsSender = smsSender;
        this.ttlSeconds = ttlSeconds;
        this.maxAttempts = maxAttempts;
        this.cooldownSeconds = cooldownSeconds;
    }

    public enum VerifyResult {
        OK, INVALID, EXPIRED, TOO_MANY_ATTEMPTS
    }

    public String issue(String phone, PhonePurpose purpose) {
        String codeKey = codeKey(purpose, phone);
        String attemptsKey = attemptsKey(purpose, phone);
        String cooldownKey = COOLDOWN_PREFIX + phone;
        String code;
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                    cooldownKey, "1", Duration.ofSeconds(cooldownSeconds));
            if (!Boolean.TRUE.equals(acquired)) {
                Long ttl = redisTemplate.getExpire(cooldownKey, TimeUnit.SECONDS);
                long retryAfter = ttl == null || ttl <= 0 ? cooldownSeconds : ttl;
                throw new BusinessException(ApiErrorCode.CODE_SEND_TOO_FREQUENT,
                        ApiErrorCode.CODE_SEND_TOO_FREQUENT.getDefaultMessage(), retryAfter);
            }
            code = String.format("%06d", RANDOM.nextInt(1_000_000));
            redisTemplate.opsForValue().set(codeKey, code, Duration.ofSeconds(ttlSeconds));
            redisTemplate.delete(attemptsKey);
        } catch (RedisConnectionFailureException ex) {
            throw new BusinessException(ApiErrorCode.DEPENDENCY_UNAVAILABLE, "验证码服务暂不可用");
        }
        try {
            smsSender.send(phone, code);
        } catch (RuntimeException ex) {
            safeDelete(codeKey);
            safeDelete(cooldownKey);
            throw ex;
        }
        return code;
    }

    public VerifyResult verifyAndConsume(String phone, PhonePurpose purpose, String code) {
        String codeKey = codeKey(purpose, phone);
        String attemptsKey = attemptsKey(purpose, phone);
        try {
            Long attempts = redisTemplate.opsForValue().increment(attemptsKey);
            if (attempts != null && attempts == 1L) {
                redisTemplate.expire(attemptsKey, Duration.ofSeconds(ttlSeconds));
            }
            if (attempts != null && attempts > maxAttempts) {
                redisTemplate.delete(codeKey);
                return VerifyResult.TOO_MANY_ATTEMPTS;
            }
            String stored = redisTemplate.opsForValue().get(codeKey);
            if (stored == null) {
                return VerifyResult.EXPIRED;
            }
            if (!stored.equals(code)) {
                return VerifyResult.INVALID;
            }
            redisTemplate.delete(codeKey);
            redisTemplate.delete(attemptsKey);
            return VerifyResult.OK;
        } catch (RedisConnectionFailureException ex) {
            throw new BusinessException(ApiErrorCode.DEPENDENCY_UNAVAILABLE, "验证码服务暂不可用");
        }
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public long cooldownSeconds() {
        return cooldownSeconds;
    }

    private void safeDelete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException ignored) {
            // 清理失败不改变已确定的错误结果
        }
    }

    private static String codeKey(PhonePurpose purpose, String phone) {
        return CODE_PREFIX + purpose.name() + ":" + phone;
    }

    private static String attemptsKey(PhonePurpose purpose, String phone) {
        return ATTEMPT_PREFIX + purpose.name() + ":" + phone;
    }
}