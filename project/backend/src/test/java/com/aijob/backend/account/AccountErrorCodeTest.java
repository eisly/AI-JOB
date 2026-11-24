package com.aijob.backend.account;

import com.aijob.backend.common.ApiErrorCode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AccountErrorCodeTest {

    @Test
    void newKeysAreUnique() {
        Set<String> keys = new HashSet<>();
        for (ApiErrorCode value : ApiErrorCode.values()) {
            assertThat(keys.add(value.getKey())).as("duplicate key %s", value.getKey()).isTrue();
        }
    }

    @Test
    void numericCodesAreUnique() {
        Set<Integer> codes = new HashSet<>();
        for (ApiErrorCode value : ApiErrorCode.values()) {
            assertThat(codes.add(value.getCode())).as("duplicate code %s", value.getCode()).isTrue();
        }
    }

    @Test
    void retryableFlagsMatchDesign() {
        assertThat(ApiErrorCode.PASSWORD_ATTEMPT_LIMIT.isRetryable()).isTrue();
        assertThat(ApiErrorCode.PHONE_BINDING_UNAVAILABLE.isRetryable()).isTrue();
        assertThat(ApiErrorCode.CSRF_INVALID.isRetryable()).isFalse();
        assertThat(ApiErrorCode.CURRENT_PASSWORD_INCORRECT.getHttpStatus()).isEqualTo(400);
        assertThat(ApiErrorCode.PASSWORD_ATTEMPT_LIMIT.getCode()).isEqualTo(42903);
        assertThat(ApiErrorCode.PHONE_BINDING_UNAVAILABLE.getHttpStatus()).isEqualTo(503);
    }
}