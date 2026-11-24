package com.aijob.backend.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record BindPhoneRequest(
        @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$", message = "请输入正确的 11 位手机号") String phone,
        @NotBlank @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字") String code) {
}