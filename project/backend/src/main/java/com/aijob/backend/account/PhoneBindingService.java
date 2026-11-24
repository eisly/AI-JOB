package com.aijob.backend.account;

import com.aijob.backend.account.dto.SendPhoneCodeResponse;
import com.aijob.backend.user.entity.AppUser;
import com.aijob.backend.user.entity.AppUserAuth;
import com.aijob.backend.user.mapper.AppUserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * 手机号绑定 / 换绑 / 解绑：唯一性预检、验证码用途隔离、当前密码校验与乐观并发。
 */
@Service
public class PhoneBindingService {

    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    private final AppUserMapper appUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final PasswordAttemptGuard passwordAttemptGuard;
    private final PhoneVerificationCodeService phoneVerificationCodeService;
    private final SmsSender smsSender;
    private final AccountProfileService accountProfileService;
    private final AccountAuditService accountAuditService;
    private final boolean exposeCodeInResponse;

    public PhoneBindingService(AppUserMapper appUserMapper,
                               PasswordEncoder passwordEncoder,
                               PasswordAttemptGuard passwordAttemptGuard,
                               PhoneVerificationCodeService phoneVerificationCodeService,
                               SmsSender smsSender,
                               AccountProfileService accountProfileService,
                               AccountAuditService accountAuditService,
                               @Value("${app.sms.expose-code-in-response:false}") boolean exposeCodeInResponse) {
        this.appUserMapper = appUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.passwordAttemptGuard = passwordAttemptGuard;
        this.phoneVerificationCodeService = phoneVerificationCodeService;
        this.smsSender = smsSender;
        this.accountProfileService = accountProfileService;
        this.accountAuditService = accountAuditService;
        this.exposeCodeInResponse = exposeCodeInResponse;
    }

    public SendPhoneCodeResponse sendCode(String userId, String phone, PhonePurpose purpose) {
        validatePhone(phone);
        if (!smsSender.available()) {
            throw AccountExceptionFactory.phoneBindingUnavailable();
        }
        ensurePhoneAvailable(userId, phone);
        String code = phoneVerificationCodeService.issue(phone, purpose);
        accountAuditService.record(userId, actionOf(purpose), AccountAction.Result.SUCCESS,
                "CODE_SENT", PhoneMask.mask(phone));
        String devCode = exposeCodeInResponse ? code : null;
        return new SendPhoneCodeResponse(phoneVerificationCodeService.cooldownSeconds(),
                phoneVerificationCodeService.ttlSeconds(), devCode);
    }

    @Transactional
    public AccountProfileVO bind(String userId, String phone, String code) {
        validatePhone(phone);
        ensurePhoneAvailable(userId, phone);
        verifyCode(userId, phone, code, PhonePurpose.BIND, AccountAction.PHONE_BIND);
        try {
            int updated = appUserMapper.bindPhone(userId, phone, Instant.now());
            if (updated == 0) {
                throw AccountExceptionFactory.phoneChanged();
            }
        } catch (DuplicateKeyException ex) {
            accountAuditService.record(userId, AccountAction.PHONE_BIND, AccountAction.Result.FAILED,
                    "PHONE_ALREADY_BOUND", PhoneMask.mask(phone));
            throw AccountExceptionFactory.phoneAlreadyBound();
        }
        accountAuditService.record(userId, AccountAction.PHONE_BIND, AccountAction.Result.SUCCESS,
                null, PhoneMask.mask(phone));
        return accountProfileService.getProfile(userId);
    }

    @Transactional
    public AccountProfileVO rebind(String userId, String currentPassword, String phone, String code) {
        requirePassword(userId, currentPassword, AccountAction.PHONE_REBIND);
        validatePhone(phone);
        ensurePhoneAvailable(userId, phone);
        AppUser current = appUserMapper.selectById(userId);
        if (current == null) {
            throw AccountExceptionFactory.userNotFound();
        }
        String currentPhone = current.getPhone();
        if (currentPhone == null) {
            throw AccountExceptionFactory.phoneChanged();
        }
        verifyCode(userId, phone, code, PhonePurpose.REBIND, AccountAction.PHONE_REBIND);
        try {
            int updated = appUserMapper.rebindPhone(userId, phone, Instant.now(), currentPhone);
            if (updated == 0) {
                throw AccountExceptionFactory.phoneChanged();
            }
        } catch (DuplicateKeyException ex) {
            accountAuditService.record(userId, AccountAction.PHONE_REBIND, AccountAction.Result.FAILED,
                    "PHONE_ALREADY_BOUND", PhoneMask.mask(phone));
            throw AccountExceptionFactory.phoneAlreadyBound();
        }
        passwordAttemptGuard.reset(userId);
        accountAuditService.record(userId, AccountAction.PHONE_REBIND, AccountAction.Result.SUCCESS,
                null, PhoneMask.mask(phone));
        return accountProfileService.getProfile(userId);
    }

    @Transactional
    public AccountProfileVO unbind(String userId, String currentPassword) {
        requirePassword(userId, currentPassword, AccountAction.PHONE_UNBIND);
        AppUser current = appUserMapper.selectById(userId);
        if (current == null) {
            throw AccountExceptionFactory.userNotFound();
        }
        String currentPhone = current.getPhone();
        if (currentPhone == null) {
            return accountProfileService.getProfile(userId);
        }
        int updated = appUserMapper.unbindPhone(userId, currentPhone);
        if (updated == 0) {
            throw AccountExceptionFactory.phoneChanged();
        }
        passwordAttemptGuard.reset(userId);
        accountAuditService.record(userId, AccountAction.PHONE_UNBIND, AccountAction.Result.SUCCESS,
                null, PhoneMask.mask(currentPhone));
        return accountProfileService.getProfile(userId);
    }

    private void requirePassword(String userId, String rawPassword, AccountAction action) {
        AppUserAuth auth = appUserMapper.selectAuthById(userId);
        if (auth == null) {
            throw AccountExceptionFactory.userNotFound();
        }
        if (rawPassword == null || !passwordEncoder.matches(rawPassword, auth.getPasswordHash())) {
            passwordAttemptGuard.recordFailure(userId);
            accountAuditService.record(userId, action, AccountAction.Result.FAILED,
                    "CURRENT_PASSWORD_INCORRECT", null);
            throw AccountExceptionFactory.currentPasswordIncorrect();
        }
    }

    private void verifyCode(String userId, String phone, String code, PhonePurpose purpose, AccountAction action) {
        PhoneVerificationCodeService.VerifyResult result =
                phoneVerificationCodeService.verifyAndConsume(phone, purpose, code);
        if (result == PhoneVerificationCodeService.VerifyResult.OK) {
            return;
        }
        String reason = result.name();
        accountAuditService.record(userId, action, AccountAction.Result.FAILED, reason, PhoneMask.mask(phone));
        switch (result) {
            case EXPIRED -> throw AccountExceptionFactory.verificationCodeExpired();
            case TOO_MANY_ATTEMPTS -> throw AccountExceptionFactory.verificationCodeTooMany();
            default -> throw AccountExceptionFactory.invalidVerificationCode();
        }
    }

    private void ensurePhoneAvailable(String userId, String phone) {
        String ownerId = appUserMapper.selectIdByPhone(phone);
        if (ownerId != null && !ownerId.equals(userId)) {
            throw AccountExceptionFactory.phoneAlreadyBound();
        }
        if (ownerId != null) {
            throw AccountExceptionFactory.phoneAlreadyBound();
        }
    }

    private static void validatePhone(String phone) {
        if (phone == null || !PHONE_PATTERN.matcher(phone).matches()) {
            throw AccountExceptionFactory.invalidPhone();
        }
    }

    private static AccountAction actionOf(PhonePurpose purpose) {
        return purpose == PhonePurpose.REBIND ? AccountAction.PHONE_REBIND : AccountAction.PHONE_BIND;
    }
}