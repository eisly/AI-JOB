package com.aijob.backend.interview.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.aijob.backend.common.BusinessException;

/**
 * 语音接口频次控制。
 * 与登录限流不同：Redis 故障时放行并告警，避免可选能力阻断面试主链路。
 */
@Service
public class VoiceRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(VoiceRateLimiter.class);
    private static final int ASR_LIMIT = 30;
    private static final int ASR_WINDOW_SECONDS = 600;
    private static final int ASR_DAILY_LIMIT = 200;
    private static final int ASR_DAY_SECONDS = 86_400;
    private static final long TTS_DAILY_CHARS = 20_000;

    private final StringRedisTemplate redis;

    public VoiceRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void checkTranscription(String userId) {
        ensure("career:rate:voice:asr:" + userId, ASR_LIMIT, ASR_WINDOW_SECONDS);
        ensure("career:rate:voice:asr:day:" + userId, ASR_DAILY_LIMIT, ASR_DAY_SECONDS);
    }

    public void checkSynthesis(String userId, int chars) {
        try {
            String key = "career:rate:voice:tts:chars:" + userId;
            Long value = redis.opsForValue().increment(key, chars);
            if (value != null && value == chars) redis.expire(key, java.time.Duration.ofSeconds(ASR_DAY_SECONDS));
            if (value != null && value > TTS_DAILY_CHARS) {
                throw BusinessException.rateLimited(ASR_DAY_SECONDS);
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("voice rate limiter unavailable op=TTS error={} action=PASS", ex.getClass().getSimpleName());
        }
    }

    private void ensure(String key, int limit, int windowSeconds) {
        try {
            Long value = redis.opsForValue().increment(key);
            if (value != null && value == 1) redis.expire(key, java.time.Duration.ofSeconds(windowSeconds));
            if (value != null && value > limit) {
                Long ttl = redis.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
                throw BusinessException.rateLimited(ttl == null || ttl <= 0 ? windowSeconds : ttl);
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("voice rate limiter unavailable op=ASR error={} action=PASS", ex.getClass().getSimpleName());
        }
    }
}