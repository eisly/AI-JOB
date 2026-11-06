package com.aijob.backend.auth.vo;

import java.time.Instant;

public record UserVO(String id, String email, String nickname, String role, Instant createdAt) {
}