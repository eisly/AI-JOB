package com.aijob.backend.user.entity;

import java.time.Instant;

/**
 * app_user 表实体。
 * 依赖 MyBatis 的 map-underscore-to-camel-case，password_hash 等敏感字段不查询、不映射。
 */
public class AppUser {

    private String id;
    private String email;
    private String nickname;
    private String phone;
    private Instant phoneBoundAt;
    private String avatarStorageKey;
    private String avatarMimeType;
    private Long avatarSizeBytes;
    private Integer avatarVersion;
    private Instant profileUpdatedAt;
    private String role;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;

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

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Instant getPhoneBoundAt() {
        return phoneBoundAt;
    }

    public void setPhoneBoundAt(Instant phoneBoundAt) {
        this.phoneBoundAt = phoneBoundAt;
    }

    public String getAvatarStorageKey() {
        return avatarStorageKey;
    }

    public void setAvatarStorageKey(String avatarStorageKey) {
        this.avatarStorageKey = avatarStorageKey;
    }

    public String getAvatarMimeType() {
        return avatarMimeType;
    }

    public void setAvatarMimeType(String avatarMimeType) {
        this.avatarMimeType = avatarMimeType;
    }

    public Long getAvatarSizeBytes() {
        return avatarSizeBytes;
    }

    public void setAvatarSizeBytes(Long avatarSizeBytes) {
        this.avatarSizeBytes = avatarSizeBytes;
    }

    public Integer getAvatarVersion() {
        return avatarVersion;
    }

    public void setAvatarVersion(Integer avatarVersion) {
        this.avatarVersion = avatarVersion;
    }

    public Instant getProfileUpdatedAt() {
        return profileUpdatedAt;
    }

    public void setProfileUpdatedAt(Instant profileUpdatedAt) {
        this.profileUpdatedAt = profileUpdatedAt;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}