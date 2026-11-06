package com.aijob.backend.job;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class MatchScoreServiceTest {

    private final MatchScoreService service = new MatchScoreService();

    @Test
    void usesFixedWeightsAndHalfUpRounding() {
        Integer score = service.score(new BigDecimal("0.8"), new BigDecimal("0.9"), BigDecimal.ONE);

        assertEquals(86, score);
    }

    @Test
    void treatsRequiredSkillsAsASet() {
        BigDecimal skill = service.skill(List.of("java", "redis"), List.of("java", "java", "mysql"));

        assertTrue(new BigDecimal("0.500000").compareTo(skill) == 0);
    }

    @Test
    void doesNotRedistributeNullComponents() {
        assertNull(service.skill(List.of("java"), List.of()));
        assertNull(service.preference(null, "JAVA_BACKEND", List.of("330100"), "330100"));
        assertNull(service.score(BigDecimal.ONE, null, BigDecimal.ONE));
    }

    @Test
    void averagesRoleAndCityPreference() {
        BigDecimal value = service.preference("JAVA_BACKEND", "JAVA_BACKEND", List.of("330100"), "110100");

        assertTrue(new BigDecimal("0.500000").compareTo(value) == 0);
    }
}