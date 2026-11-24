package com.aijob.backend.account;

import java.util.List;

/**
 * 账号能力探测结果：用于前端置灰不可用入口，禁止"点击无响应"。
 */
public record AccountCapabilitiesVO(
        boolean smsBindingAvailable,
        boolean avatarUploadAvailable,
        long avatarMaxBytes,
        List<String> avatarAcceptTypes) {
}