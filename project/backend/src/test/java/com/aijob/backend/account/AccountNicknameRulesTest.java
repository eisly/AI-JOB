package com.aijob.backend.account;

import com.aijob.backend.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountNicknameRulesTest {

    @Test
    void trimsAndAcceptsBoundaries() {
        assertThat(NicknameRules.normalize("  张  ")).isEqualTo("张");
        assertThat(NicknameRules.normalize("a".repeat(30))).hasSize(30);
    }

    @Test
    void rejectsEmptyAndAllSpaces() {
        assertThatThrownBy(() -> NicknameRules.normalize("   "))
                .isInstanceOf(BusinessException.class)
                .extracting("errorKey").isEqualTo("VALIDATION_ERROR");
        assertThatThrownBy(() -> NicknameRules.normalize(null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsTooLong() {
        BusinessException ex = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> NicknameRules.normalize("a".repeat(31)), BusinessException.class);
        assertThat(ex.getErrorKey()).isEqualTo("VALIDATION_ERROR");
        assertThat(ex.getDetails().toString()).contains("30");
    }

    @Test
    void rejectsControlCharacters() {
        BusinessException newline = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> NicknameRules.normalize("昵称\n换行"), BusinessException.class);
        assertThat(newline.getDetails().toString()).contains("不支持");
        BusinessException tab = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> NicknameRules.normalize("昵称\t制表"), BusinessException.class);
        assertThat(tab.getErrorKey()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void emailTextCanBeNickname() {
        assertThat(NicknameRules.normalize("user@example.com")).isEqualTo("user@example.com");
    }
}