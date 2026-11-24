package com.aijob.backend.auth.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 既有响应契约保持不变：原 5 个字段语义与类型不变，新增字段为可选（为 null 时不输出）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserVO(String id, String email, String nickname, String role, Instant createdAt,
                     String phoneMasked, String avatarUrl, Integer avatarVersion, Instant profileUpdatedAt) {

    public UserVO(String id, String email, String nickname, String role, Instant createdAt) {
        this(id, email, nickname, role, createdAt, null, null, null, null);
    }
}