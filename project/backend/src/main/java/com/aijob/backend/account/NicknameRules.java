package com.aijob.backend.account;

import com.aijob.backend.common.BusinessException;

import java.util.regex.Pattern;

/**
 * 昵称规则：去首尾空格后 1-30 个字符，拒绝控制字符，允许任意可见字符与中间空格，不要求唯一。
 * 账号页与既有 PATCH /users/me 共用本规则，保证两个入口行为一致。
 */
public final class NicknameRules {

    public static final int MAX_LENGTH = 30;

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x1F\\x7F]");

    private NicknameRules() {
    }

    public static String normalize(String rawNickname) {
        if (rawNickname == null) {
            throw BusinessException.validation("nickname", "昵称不能为空");
        }
        String trimmed = rawNickname.trim();
        if (trimmed.isEmpty()) {
            throw BusinessException.validation("nickname", "昵称不能为空");
        }
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length > MAX_LENGTH) {
            throw BusinessException.validation("nickname", "昵称不能超过 30 个字符");
        }
        if (CONTROL_CHARS.matcher(trimmed).find()) {
            throw BusinessException.validation("nickname", "昵称包含不支持的字符");
        }
        return trimmed;
    }
}