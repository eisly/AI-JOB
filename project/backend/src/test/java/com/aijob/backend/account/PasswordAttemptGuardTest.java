package com.aijob.backend.account;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.user.entity.AppUserAuth;
import com.aijob.backend.user.mapper.AppUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordAttemptGuardTest {

    @Mock
    private AppUserMapper appUserMapper;

    private PasswordAttemptGuard guard;

    @BeforeEach
    void setUp() {
        guard = new PasswordAttemptGuard(appUserMapper, 5, 900);
    }

    @Test
    void locksAfterReachingMaxFailures() {
        when(appUserMapper.selectPasswordFailureCount("u1")).thenReturn(5);

        int attempts = guard.recordFailure("u1");

        assertThat(attempts).isEqualTo(5);
        verify(appUserMapper).lockPassword(eq("u1"), any(Instant.class));
    }

    @Test
    void doesNotLockBelowMaxFailures() {
        when(appUserMapper.selectPasswordFailureCount("u1")).thenReturn(3);

        guard.recordFailure("u1");

        verify(appUserMapper, org.mockito.Mockito.never()).lockPassword(eq("u1"), any(Instant.class));
    }

    @Test
    void rejectsDuringLockWindow() {
        AppUserAuth auth = new AppUserAuth();
        auth.setPasswordLockedUntil(Instant.now().plusSeconds(60));
        when(appUserMapper.selectAuthById("u1")).thenReturn(auth);

        assertThatThrownBy(() -> guard.checkLocked("u1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getRetryAfterSeconds()).isNotNull())
                .extracting("errorKey").isEqualTo("PASSWORD_ATTEMPT_LIMIT");
    }

    @Test
    void allowsAfterLockExpires() {
        AppUserAuth auth = new AppUserAuth();
        auth.setPasswordLockedUntil(Instant.now().minusSeconds(1));
        when(appUserMapper.selectAuthById("u1")).thenReturn(auth);

        guard.checkLocked("u1");
    }

    @Test
    void resetClearsCounter() {
        guard.reset("u1");
        verify(appUserMapper).clearPasswordFailure("u1");
    }
}