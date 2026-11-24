package com.aijob.backend.account;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceDigestTest {

    @Test
    void digestIsStableAndFixedLength() {
        String first = SourceDigest.of("127.0.0.1", "JUnit");
        String second = SourceDigest.of("127.0.0.1", "JUnit");
        assertThat(first).isEqualTo(second).hasSize(32);
    }

    @Test
    void differentInputsProduceDifferentDigests() {
        assertThat(SourceDigest.of("127.0.0.1", "A"))
                .isNotEqualTo(SourceDigest.of("127.0.0.1", "B"));
    }

    @Test
    void digestDoesNotContainRawInput() {
        String digest = SourceDigest.of("13812345678", "Agent/1.0");
        assertThat(digest).doesNotContain("13812345678").doesNotContain("Agent");
    }
}