package com.aijob.backend.auth.controller;

import com.aijob.backend.auth.dto.ForgotPasswordRequest;
import com.aijob.backend.auth.dto.LoginRequest;
import com.aijob.backend.auth.dto.RegisterRequest;
import com.aijob.backend.auth.dto.ResetPasswordRequest;
import com.aijob.backend.auth.security.UserPrincipal;
import com.aijob.backend.auth.service.AuthService;
import com.aijob.backend.auth.vo.CsrfVO;
import com.aijob.backend.auth.vo.TokenVO;
import com.aijob.backend.auth.vo.UserVO;
import com.aijob.backend.common.CommonResult;
import com.aijob.backend.config.AppProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public static final String CSRF_HEADER = "X-CSRF-Token";
    public static final String CSRF_COOKIE = "X-CSRF-Token";

    private final AuthService authService;
    private final AppProperties properties;

    public AuthController(AuthService authService, AppProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    @PostMapping("/register")
    public CommonResult<UserVO> register(@Valid @RequestBody RegisterRequest request) {
        return CommonResult.success(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<CommonResult<TokenVO>> login(@Valid @RequestBody LoginRequest request,
                                                       HttpServletRequest httpRequest) {
        AuthService.LoginOutcome outcome = authService.login(request, clientIp(httpRequest));
        return tokenResponse(outcome);
    }

    @PostMapping("/refresh")
    public ResponseEntity<CommonResult<TokenVO>> refresh(
            @RequestHeader(name = CSRF_HEADER, required = false) String csrfToken,
            HttpServletRequest httpRequest) {
        AuthService.LoginOutcome outcome = authService.refresh(readRefreshCookie(httpRequest), csrfToken);
        return tokenResponse(outcome);
    }

    @GetMapping("/csrf")
    public ResponseEntity<CommonResult<CsrfVO>> csrf(HttpServletRequest httpRequest) {
        String csrfToken = authService.csrf(readRefreshCookie(httpRequest));
        return ResponseEntity.ok()
                .header(CSRF_HEADER, csrfToken)
                .body(CommonResult.success(new CsrfVO(csrfToken)));
    }

    @PostMapping("/logout")
    public ResponseEntity<CommonResult<Boolean>> logout(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestHeader(name = CSRF_HEADER, required = false) String csrfToken,
            HttpServletRequest httpRequest) {
        authService.logout(readRefreshCookie(httpRequest), csrfToken, principal);
        ResponseCookie cleared = buildRefreshCookie("", Instant.EPOCH);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cleared.toString())
                .body(CommonResult.success(true));
    }

    @PostMapping("/password/forgot")
    public CommonResult<Boolean> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return CommonResult.success(true);
    }

    @PostMapping("/password/reset")
    public CommonResult<Boolean> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return CommonResult.success(true);
    }

    private ResponseEntity<CommonResult<TokenVO>> tokenResponse(AuthService.LoginOutcome outcome) {
        TokenVO tokenVO = new TokenVO(outcome.accessToken(), outcome.expiresIn(), outcome.user());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(outcome.refreshToken(), outcome.refreshExpiresAt()).toString())
                .header(CSRF_HEADER, outcome.csrfToken())
                .body(CommonResult.success(tokenVO));
    }

    private ResponseCookie buildRefreshCookie(String value, Instant expiresAt) {
        long maxAge = Math.max(0, Duration.between(Instant.now(), expiresAt).getSeconds());
        return ResponseCookie.from(properties.session().cookieName(), value)
                .httpOnly(true)
                .secure(properties.session().secure())
                .sameSite(properties.session().sameSite())
                .path(properties.session().cookiePath())
                .maxAge(Duration.ofSeconds(maxAge))
                .build();
    }

    private String readRefreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (properties.session().cookieName().equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}