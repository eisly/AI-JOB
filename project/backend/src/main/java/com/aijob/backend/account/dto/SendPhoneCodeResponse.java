package com.aijob.backend.account.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SendPhoneCodeResponse(long retryAfterSeconds, long expiresInSeconds, String devCode) {
}