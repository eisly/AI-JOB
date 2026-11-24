package com.aijob.backend.account;

import com.aijob.backend.account.dto.ChangePasswordResponse;
import com.aijob.backend.auth.service.SessionService;
import com.aijob.backend.common.ApiErrorCode;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.user.entity.AppUserAuth;
import com.aijob.backend.user.mapper.AppUserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录态密码修改：限流检查 → 新密码规则 → 校验当前密码 → 条件更新 → 全会话失效。
 */
@Service
public class AccountCredentialService {

    private static final int MIN_LENGTH = 10;
    private static final int MAX_LENGTH = 72;

    private final AppUserMapper appUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessionService;
    private final PasswordAttemptGuard passwordAttemptGuard;
    private final AccountAuditService accountAuditService;

    public AccountCredentialService(AppUserMapper appUserMapper,
                                    PasswordEncoder passwordEncoder,
                                    SessionService sessionService,
                                    PasswordAttemptGuard passwordAttemptGuard,
                                    AccountAuditService accountAuditService) {
        this.appUserMapper = appUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.sessionService = sessionService;
        this.passwordAttemptGuard = passwordAttemptGuard;
        this.accountAuditService = accountAuditService;
    }

    @Transactional
    public ChangePasswordResponse changePassword(String userId, String currentPassword,
                                                 String newPassword, String confirmPassword) {
        passwordAttemptGuard.checkLocked(userId);
        validateNewPassword(currentPassword, newPassword, confirmPassword);

        AppUserAuth auth = appUserMapper.selectAuthById(userId);
        if (auth == null) {
            throw AccountExceptionFactory.userNotFound();
        }
        if (!passwordEncoder.matches(currentPassword, auth.getPasswordHash())) {
            passwordAttemptGuard.recordFailure(userId);
            accountAuditService.record(userId, AccountAction.PASSWORD_CHANGE,
                    AccountAction.Result.FAILED, "CURRENT_PASSWORD_INCORRECT", null);
            throw AccountExceptionFactory.currentPasswordIncorrect();
        }

        String newHash = passwordEncoder.encode(newPassword);
        int updated = appUserMapper.updatePasswordHash(userId, newHash, auth.getPasswordHash());
        if (updated == 0) {
            throw new BusinessException(ApiErrorCode.VERSION_CONFLICT);
        }
        passwordAttemptGuard.reset(userId);
        sessionService.revokeAll(userId);
        accountAuditService.record(userId, AccountAction.PASSWORD_CHANGE, AccountAction.Result.SUCCESS, null, null);
        return new ChangePasswordResponse(true);
    }

    static void validateNewPassword(String currentPassword, String newPassword, String confirmPassword) {
        int length = newPassword == null ? 0 : newPassword.codePointCount(0, newPassword.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new BusinessException(ApiErrorCode.WEAK_PASSWORD);
        }
        if (confirmPassword == null || !newPassword.equals(confirmPassword)) {
            throw AccountExceptionFactory.passwordConfirmMismatch();
        }
        if (currentPassword != null && currentPassword.equals(newPassword)) {
            throw AccountExceptionFactory.newPasswordSame();
        }
    }
}