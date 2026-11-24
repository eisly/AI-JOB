package com.aijob.backend.account;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneMaskTest {

    @Test
    void masksElevenDigitPhone() {
        assertThat(PhoneMask.mask("13812345678")).isEqualTo("138****5678");
    }

    @Test
    void nullStaysNull() {
        assertThat(PhoneMask.mask(null)).isNull();
    }

    @Test
    void invalidLengthReturnsNullWithoutLeaking() {
        assertThat(PhoneMask.mask("1381234")).isNull();
        assertThat(PhoneMask.mask("138123456789")).isNull();
        assertThat(PhoneMask.mask("1381234567a")).isNull();
    }
}