package com.aijob.backend.ability;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aijob.backend.common.JsonService;
import com.aijob.backend.profile.ProfileMapper;

import tools.jackson.databind.json.JsonMapper;

class AbilityServiceTest {

    private final AbilityMapper mapper = mock(AbilityMapper.class);
    private final ProfileMapper profiles = mock(ProfileMapper.class);
    private final AbilityService service = new AbilityService(mapper,
            new JsonService(JsonMapper.builder().build()), profiles);

    @Test
    void recomputesLatestEvidenceAverageAndStatus() {
        when(mapper.last20("user", "java")).thenReturn(List.of(100, 80, 70));

        service.recompute("user", "java");

        verify(mapper).saveSnapshot(eq("user"), eq("java"), eq(new BigDecimal("83.3")), eq(3), eq("SUFFICIENT"));
    }

    @Test
    void resumeClaimHasNoScoreAndDoesNotCreateAbilityScore() {
        when(mapper.last20("user", "java")).thenReturn(List.of());
        Map<String, Object> facts = Map.of("skills", List.of(Map.of(
                "skillCode", "java", "quote", "使用 Java", "claim", "熟悉 Java", "location", "p1", "confirmed", true)));

        service.replaceResumeClaims("user", "resume-version", facts);

        verify(mapper).insertEvidence(any(), eq("user"), eq("java"), eq("RESUME_CLAIM"), eq("resume-version"),
                eq(1), isNull(), eq(0), eq("使用 Java"), eq("resume-claim-v1"), isNull(), isNull(), isNull());
        verify(mapper).saveSnapshot(eq("user"), eq("java"), isNull(), eq(0), eq("MISSING"));
    }

    @Test
    void noValidEvidenceMeansMissingAndNullScore() {
        when(mapper.last20("user", "redis")).thenReturn(List.of());

        service.recompute("user", "redis");

        verify(mapper).saveSnapshot(eq("user"), eq("redis"), isNull(), eq(0), eq("MISSING"));

    }
}