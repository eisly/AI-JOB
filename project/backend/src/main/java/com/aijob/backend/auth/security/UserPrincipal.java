package com.aijob.backend.auth.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

public class UserPrincipal {

    private final String userId;
    private final String email;
    private final String nickname;
    private final String role;
    private final int tokenVersion;
    private final String jti;

    public UserPrincipal(String userId, String email, String nickname, String role, int tokenVersion, String jti) {
        this.userId = userId;
        this.email = email;
        this.nickname = nickname;
        this.role = role;
        this.tokenVersion = tokenVersion;
        this.jti = jti;
    }

    public String getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public String getNickname() {
        return nickname;
    }

    public String getRole() {
        return role;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    public String getJti() {
        return jti;
    }

    public List<GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }
}