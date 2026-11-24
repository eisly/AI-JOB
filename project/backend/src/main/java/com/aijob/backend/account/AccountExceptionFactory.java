package com.aijob.backend.account;

import com.aijob.backend.common.ApiErrorCode;
import com.aijob.backend.common.BusinessException;

/**
 * 账号模块错误码工厂：集中构造错误，避免错误码字符串散落在业务代码中。
 */
public final class AccountExceptionFactory {

    private AccountExceptionFactory() {
    }

    public static BusinessException currentPasswordIncorrect() {
        return new BusinessException(ApiErrorCode.CURRENT_PASSWORD_INCORRECT);
    }

    public static BusinessException newPasswordSame() {
        return new BusinessException(ApiErrorCode.NEW_PASSWORD_SAME);
    }

    public static BusinessException passwordConfirmMismatch() {
        return new BusinessException(ApiErrorCode.PASSWORD_CONFIRM_MISMATCH);
    }

    public static BusinessException invalidPhone() {
        return new BusinessException(ApiErrorCode.INVALID_PHONE);
    }

    public static BusinessException verificationCodeExpired() {
        return new BusinessException(ApiErrorCode.VERIFICATION_CODE_EXPIRED);
    }

    public static BusinessException verificationCodeTooMany() {
        return new BusinessException(ApiErrorCode.VERIFICATION_CODE_TOO_MANY);
    }

    public static BusinessException invalidVerificationCode() {
        return new BusinessException(ApiErrorCode.INVALID_VERIFICATION_CODE);
    }

    public static BusinessException csrfInvalid() {
        return new BusinessException(ApiErrorCode.CSRF_INVALID);
    }

    public static BusinessException phoneAlreadyBound() {
        return new BusinessException(ApiErrorCode.PHONE_ALREADY_BOUND);
    }

    public static BusinessException phoneChanged() {
        return new BusinessException(ApiErrorCode.PHONE_CHANGED);
    }

    public static BusinessException avatarTooLarge() {
        return new BusinessException(ApiErrorCode.AVATAR_TOO_LARGE);
    }

    public static BusinessException unsupportedAvatarType() {
        return new BusinessException(ApiErrorCode.UNSUPPORTED_AVATAR_TYPE);
    }

    public static BusinessException imageUnreadable() {
        return new BusinessException(ApiErrorCode.IMAGE_UNREADABLE);
    }

    public static BusinessException passwordAttemptLimit(long retryAfterSeconds) {
        return new BusinessException(ApiErrorCode.PASSWORD_ATTEMPT_LIMIT,
                ApiErrorCode.PASSWORD_ATTEMPT_LIMIT.getDefaultMessage(), retryAfterSeconds);
    }

    public static BusinessException phoneBindingUnavailable() {
        return new BusinessException(ApiErrorCode.PHONE_BINDING_UNAVAILABLE);
    }

    public static BusinessException userNotFound() {
        return new BusinessException(ApiErrorCode.RESOURCE_NOT_FOUND, "用户不存在");
    }

    public static BusinessException avatarNotFound() {
        return new BusinessException(ApiErrorCode.RESOURCE_NOT_FOUND, "头像不存在");
    }
}