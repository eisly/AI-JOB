package com.aijob.backend.account.dto;

import jakarta.validation.constraints.NotBlank;

public record UnbindPhoneRequest(@NotBlank String currentPassword) {
}