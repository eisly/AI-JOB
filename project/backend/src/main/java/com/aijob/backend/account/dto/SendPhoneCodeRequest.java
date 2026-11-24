package com.aijob.backend.account.dto;

import com.aijob.backend.account.PhonePurpose;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record SendPhoneCodeRequest(
        @NotNull @Pattern(regexp = "^1[3-9]\\d{9}$", message = "请输入正确的 11 位手机号") String phone,
        @NotNull PhonePurpose purpose) {
}