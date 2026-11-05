package com.aijob.backend.profile.support;

import com.aijob.backend.common.BizException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * 临时用户身份解析：从 X-User-Id 请求头读取 userId。
 * 认证功能落地后应改为从 Bearer accessToken 解析，并删除该请求头约定。
 */
public final class UserIdResolver {

    private UserIdResolver() {
    }

    public static String resolve(String rawUserId) {
        if (rawUserId == null || rawUserId.isBlank()) {
            throw unauthenticated("缺少 X-User-Id 请求头");
        }
        String userId = rawUserId.trim();
        try {
            UUID.fromString(userId);
        } catch (IllegalArgumentException ex) {
            throw unauthenticated("X-User-Id 不是合法的 UUID");
        }
        return userId;
    }

    private static BizException unauthenticated(String message) {
        return new BizException(HttpStatus.UNAUTHORIZED, 40101, "UNAUTHENTICATED", message);
    }
}