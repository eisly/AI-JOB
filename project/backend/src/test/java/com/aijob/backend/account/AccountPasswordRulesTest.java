package com.aijob.backend.account;

import com.aijob.backend.common.ApiErrorCode;
import com.aijob.backend.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountPasswordRulesTest {

    private static final String CURRENT = "CurrentPass_1";

    @Test
    void acceptsBoundaryLengths() {
        assertThatCode(() -> AccountCredentialService.validateNewPassword(CURRENT, "a".repeat(10), "a".repeat(10)))
                .doesNotThrowAnyException();
        assertThatCode(() -> AccountCredentialService.validateNewPassword(CURRENT, "a".repeat(72), "a".repeat(72)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsTooShort() {
        assertThatThrownBy(() -> AccountCredentialService.validateNewPassword(CURRENT, "a".repeat(9), "a".repeat(9)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorKey").isEqualTo(ApiErrorCode.WEAK_PASSWORD.getKey());
    }

    @Test
    void rejectsMismatch() {
        assertThatThrownBy(() -> AccountCredentialService.validateNewPassword(CURRENT, "a".repeat(12), "b".repeat(12)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorKey").isEqualTo("PASSWORD_CONFIRM_MISMATCH");
    }

    @Test
    void rejectsSameAsCurrent() {
        assertThatThrownBy(() -> AccountCredentialService.validateNewPassword(CURRENT, CURRENT, CURRENT))
                .isInstanceOf(BusinessException.class)
                .extracting("errorKey").isEqualTo("NEW_PASSWORD_SAME");
    }

    @Test
    void strengthHintDoesNotBlock() {
        assertThatCode(() -> AccountCredentialService.validateNewPassword(CURRENT, "aaaaaaaaaaaa", "aaaaaaaaaaaa"))
                .doesNotThrowAnyException();
    }
}