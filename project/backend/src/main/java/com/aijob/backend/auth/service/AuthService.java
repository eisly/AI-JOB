package com.aijob.backend.auth.service;

import com.aijob.backend.auth.dto.ForgotPasswordRequest;
import com.aijob.backend.auth.dto.LoginRequest;
import com.aijob.backend.auth.dto.RegisterRequest;
import com.aijob.backend.auth.dto.ResetPasswordRequest;
import com.aijob.backend.auth.security.UserPrincipal;
import com.aijob.backend.auth.vo.UserVO;

import java.time.Instant;

public interface AuthService {

    record LoginOutcome(String accessToken, long expiresIn, UserVO user,
                        String refreshToken, String csrfToken, Instant refreshExpiresAt) {
    }

    UserVO register(RegisterRequest request);

    LoginOutcome login(LoginRequest request);

    LoginOutcome refresh(String refreshToken, String csrfToken);

    String csrf(String refreshToken);

    void logout(String refreshToken, String csrfToken, UserPrincipal principal);

    void forgotPassword(ForgotPasswordRequest request);

    void resetPassword(ResetPasswordRequest request);
}
