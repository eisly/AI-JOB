package com.aijob.backend.account;

import java.time.Instant;

/**
 * 账号页唯一资料源；手机号仅输出脱敏值，头像地址仅在存在头像时返回。
 */
public record AccountProfileVO(
        String id,
        String email,
        String nickname,
        String phoneMasked,
        boolean phoneBound,
        String avatarUrl,
        int avatarVersion,
        Instant createdAt,
        Instant profileUpdatedAt) {
}