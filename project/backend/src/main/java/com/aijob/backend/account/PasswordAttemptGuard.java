package com.aijob.backend.account;

import com.aijob.backend.user.entity.AppUserAuth;
import com.aijob.backend.user.mapper.AppUserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * 当前密码失败限流：达到阈值后锁定一段时间。失败计数使用独立事务，
 * 避免密码校验失败后主事务回滚导致计数丢失。
 */
@Service
public class PasswordAttemptGuard {

    private final AppUserMapper appUserMapper;
    private final int maxFailures;
    private final long lockSeconds;

    public PasswordAttemptGuard(AppUserMapper appUserMapper,
                                @Value("${app.account.password.max-failures:5}") int maxFailures,
                                @Value("${app.account.password.lock-seconds:900}") long lockSeconds) {
        this.appUserMapper = appUserMapper;
        this.maxFailures = maxFailures;
        this.lockSeconds = lockSeconds;
    }

    public void checkLocked(String userId) {
        AppUserAuth auth = appUserMapper.selectAuthById(userId);
        if (auth == null || auth.getPasswordLockedUntil() == null) {
            return;
        }
        Instant lockedUntil = auth.getPasswordLockedUntil();
        if (lockedUntil.isAfter(Instant.now())) {
            long retryAfter = Duration.between(Instant.now(), lockedUntil).getSeconds();
            throw AccountExceptionFactory.passwordAttemptLimit(Math.max(retryAfter, 1));
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordFailure(String userId) {
        appUserMapper.incrementPasswordFailure(userId);
        Integer count = appUserMapper.selectPasswordFailureCount(userId);
        int attempts = count == null ? 0 : count;
        if (attempts >= maxFailures) {
            appUserMapper.lockPassword(userId, Instant.now().plusSeconds(lockSeconds));
        }
        return attempts;
    }

    @Transactional
    public void reset(String userId) {
        appUserMapper.clearPasswordFailure(userId);
    }

    public int maxFailures() {
        return maxFailures;
    }
}
