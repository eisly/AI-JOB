package com.aijob.backend.auth.service.impl;

import com.aijob.backend.auth.dto.ForgotPasswordRequest;
import com.aijob.backend.auth.dto.LoginRequest;
import com.aijob.backend.auth.dto.RegisterRequest;
import com.aijob.backend.auth.dto.ResetPasswordRequest;
import com.aijob.backend.auth.security.JwtService;
import com.aijob.backend.auth.security.UserPrincipal;
import com.aijob.backend.auth.service.AuthService;
import com.aijob.backend.auth.service.RateLimitService;
import com.aijob.backend.auth.service.SessionService;
import com.aijob.backend.auth.service.TokenRevocationService;
import com.aijob.backend.auth.service.VerificationCodeService;
import com.aijob.backend.auth.vo.UserVO;
import com.aijob.backend.common.ApiErrorCode;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.config.AppProperties;
import com.aijob.backend.mail.MailService;
import com.aijob.backend.user.entity.AppUserAuth;
import com.aijob.backend.user.mapper.AppUserMapper;
import com.aijob.backend.user.mapper.UserProfileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final AppUserMapper appUserMapper;
    private final UserProfileMapper userProfileMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SessionService sessionService;
    private final RateLimitService rateLimitService;
    private final VerificationCodeService verificationCodeService;
    private final TokenRevocationService tokenRevocationService;
    private final MailService mailService;
    private final AppProperties properties;

    public AuthServiceImpl(AppUserMapper appUserMapper,
                           UserProfileMapper userProfileMapper,
                           PasswordEncoder passwordEncoder,
                           JwtService jwtService,
                           SessionService sessionService,
                           RateLimitService rateLimitService,
                           VerificationCodeService verificationCodeService,
                           TokenRevocationService tokenRevocationService,
                           MailService mailService,
                           AppProperties properties) {
        this.appUserMapper = appUserMapper;
        this.userProfileMapper = userProfileMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.sessionService = sessionService;
        this.rateLimitService = rateLimitService;
        this.verificationCodeService = verificationCodeService;
        this.tokenRevocationService = tokenRevocationService;
        this.mailService = mailService;
        this.properties = properties;
    }

    @Override
    @Transactional
    public UserVO register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        validatePassword(request.password());
        if (appUserMapper.selectAuthByEmail(email) != null) {
            throw new BusinessException(ApiErrorCode.EMAIL_ALREADY_EXISTS);
        }
        AppUserAuth user = new AppUserAuth();
        user.setId(UUID.randomUUID().toString());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(request.nickname().trim());
        user.setRole("USER");
        user.setStatus("ACTIVE");
        user.setTokenVersion(0);
        try {
            appUserMapper.insert(user);
            userProfileMapper.insertEmpty(user.getId());
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ApiErrorCode.EMAIL_ALREADY_EXISTS);
        }
        AppUserAuth created = appUserMapper.selectAuthById(user.getId());
        return toUserVO(created);
    }

    @Override
    public LoginOutcome login(LoginRequest request, String clientIp) {
        String email = normalizeEmail(request.email());
        long retryAfter = rateLimitService.retryAfterSeconds(email, clientIp);
        if (retryAfter > 0) {
            throw new BusinessException(ApiErrorCode.RATE_LIMITED, "登录尝试过于频繁，请稍后再试", retryAfter);
        }
        AppUserAuth user = appUserMapper.selectAuthByEmail(email);
        boolean credentialsMatch = user != null
                && user.getPasswordHash() != null
                && passwordEncoder.matches(request.password(), user.getPasswordHash());
        if (!credentialsMatch || !"ACTIVE".equals(user.getStatus())) {
            rateLimitService.recordFailure(email, clientIp);
            throw new BusinessException(ApiErrorCode.UNAUTHENTICATED, "邮箱或密码错误");
        }
        String accessToken = jwtService.issue(user.getId(), user.getRole(), user.getTokenVersion());
        SessionService.IssuedSession session = sessionService.issue(user.getId());
        return new LoginOutcome(accessToken, jwtService.accessTtlSeconds(), toUserVO(user),
                session.refreshToken(), session.csrfToken(), session.expiresAt());
    }

    @Override
    public LoginOutcome refresh(String refreshToken, String csrfToken) {
        SessionService.RotatedSession rotated = sessionService.rotate(refreshToken, csrfToken);
        AppUserAuth user = appUserMapper.selectAuthById(rotated.userId());
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(ApiErrorCode.UNAUTHENTICATED, "用户不可用");
        }
        String accessToken = jwtService.issue(user.getId(), user.getRole(), user.getTokenVersion());
        return new LoginOutcome(accessToken, jwtService.accessTtlSeconds(), toUserVO(user),
                rotated.refreshToken(), rotated.csrfToken(), rotated.expiresAt());
    }

    @Override
    public String csrf(String refreshToken) {
        return sessionService.rotateCsrf(refreshToken);
    }

    @Override
    public void logout(String refreshToken, String csrfToken, UserPrincipal principal) {
        sessionService.requireCsrf(refreshToken, csrfToken);
        sessionService.revoke(refreshToken);
        if (principal != null) {
            tokenRevocationService.revoke(principal.getJti(), jwtService.accessTtlSeconds());
        }
    }

    @Override
    public void forgotPassword(ForgotPasswordRequest request) {
        String email = normalizeEmail(request.email());
        String code = verificationCodeService.issue(email);
        AppUserAuth user = appUserMapper.selectAuthByEmail(email);
        if (user != null && "ACTIVE".equals(user.getStatus())) {
            try {
                mailService.sendResetCode(user.getEmail(), code);
            } catch (BusinessException ex) {
                // M02：邮件发送失败不得改变响应——"已注册 503 / 未注册 200"会泄露账号存在性。
                // 统一按受理成功返回（前端文案为"若该邮箱已注册，验证码已发送"），
                // 真实失败记录日志供排查；60 秒冷却已防止对故障服务的重复轰炸。
                log.error("reset code mail failed for registered account, response masked uniformly", ex);
            }
        }
        if (properties.mail().exposeCodeInResponse()) {
            log.info("reset code for {} is {}", email, code);
        }
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        String email = normalizeEmail(request.email());
        validatePassword(request.newPassword());
        VerificationCodeService.VerifyResult result = verificationCodeService.verifyAndConsume(email, request.code());
        if (result != VerificationCodeService.VerifyResult.OK) {
            throw new BusinessException(ApiErrorCode.INVALID_VERIFICATION_CODE);
        }
        AppUserAuth user = appUserMapper.selectAuthByEmail(email);
        if (user == null) {
            throw new BusinessException(ApiErrorCode.INVALID_VERIFICATION_CODE);
        }
        appUserMapper.updatePasswordAndBumpVersion(user.getId(), passwordEncoder.encode(request.newPassword()));
        sessionService.revokeAll(user.getId());
    }

    private static UserVO toUserVO(AppUserAuth user) {
        return new UserVO(user.getId(), user.getEmail(), user.getNickname(), user.getRole(), user.getCreatedAt());
    }

    private static void validatePassword(String password) {
        if (password == null) {
            throw new BusinessException(ApiErrorCode.WEAK_PASSWORD);
        }
        int length = password.codePointCount(0, password.length());
        if (length < 10 || length > 72) {
            throw new BusinessException(ApiErrorCode.WEAK_PASSWORD);
        }
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}