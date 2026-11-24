package com.aijob.backend.user.entity;

import java.time.Instant;

public class AppUserAuth {

    private String id;
    private String email;
    private String passwordHash;
    private String nickname;
    private String role;
    private String status;
    private int tokenVersion;
    private int passwordFailedAttempts;
    private Instant passwordLockedUntil;
    private Instant createdAt;
    private Instant deletedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    public void setTokenVersion(int tokenVersion) {
        this.tokenVersion = tokenVersion;
    }

    public int getPasswordFailedAttempts() {
        return passwordFailedAttempts;
    }

    public void setPasswordFailedAttempts(int passwordFailedAttempts) {
        this.passwordFailedAttempts = passwordFailedAttempts;
    }

    public Instant getPasswordLockedUntil() {
        return passwordLockedUntil;
    }

    public void setPasswordLockedUntil(Instant passwordLockedUntil) {
        this.passwordLockedUntil = passwordLockedUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}