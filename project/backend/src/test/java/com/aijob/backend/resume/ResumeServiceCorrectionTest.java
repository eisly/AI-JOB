package com.aijob.backend.resume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aijob.backend.ability.AbilityService;
import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.profile.ProfileService;

import tools.jackson.databind.json.JsonMapper;

class ResumeServiceCorrectionTest {
    @Test
    void correctsActiveVersionAndKeepsOriginalSnapshot() {
        ResumeMapper mapper = mock(ResumeMapper.class);
        ProfileService profiles = mock(ProfileService.class);
        AbilityService ability = mock(AbilityService.class);
        JsonService json = new JsonService(JsonMapper.builder().build());
        ResumeService service = new ResumeService(mapper, null, json, new ApiView(json), null, null, null, profiles, ability);
        Map<String, Object> version = new HashMap<>();
        version.put("status", "CONFIRMED");
        version.put("confirmed_facts_json", json.stringify(Map.of("experienceMonths", 0, "skills", List.of())));
        when(mapper.lockVersion("version", "user")).thenReturn(version);
        when(profiles.locked("user")).thenReturn(Map.of("profile_version", 5L, "active_resume_version_id", "version"));
        when(mapper.correctFacts(eq("version"), anyString())).thenReturn(1);
        when(profiles.get("user")).thenReturn(Map.of("profileVersion", 6));

        Map<String, Object> evidence = Map.of("claim", "校级一等奖学金", "quote", "2026 年获一等奖学金",
                "location", "paragraph:4", "confirmed", true);
        Map<String, Object> changed = Map.of("experienceMonths", 12, "skills", List.of(),
                "internships", List.of(Map.of("claim", "后端开发实习", "quote", "后端开发实习",
                        "location", "paragraph:2", "confirmed", true)), "awards", List.of(evidence));
        assertEquals(6, service.correctFacts("user", "version", Map.of(
                "expectedProfileVersion", 5, "reason", "核对劳动合同", "facts", changed)).get("profileVersion"));
        verify(mapper).insertFactRevision(anyString(), eq("version"), eq(1), isNull(), eq(String.valueOf(version.get("confirmed_facts_json"))), eq("首次确认的事实"));
        verify(mapper).insertFactRevision(anyString(), eq("version"), eq(2), eq(6L), anyString(), eq("核对劳动合同"));
        verify(profiles).confirmFacts(eq("user"), eq("version"), anyMap(), eq(5L));
        verify(ability).replaceResumeClaims(eq("user"), eq("version"), anyMap());
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(mapper).correctFacts(eq("version"), saved.capture());
        assertEquals(1, ((List<?>) json.map(saved.getValue()).get("internships")).size());
        assertEquals(1, ((List<?>) json.map(saved.getValue()).get("awards")).size());
    }

    @Test
    void rejectsCorrectionOfInactiveVersion() {
        ResumeMapper mapper = mock(ResumeMapper.class);
        ProfileService profiles = mock(ProfileService.class);
        JsonService json = new JsonService(JsonMapper.builder().build());
        ResumeService service = new ResumeService(mapper, null, json, new ApiView(json), null, null, null, profiles, mock(AbilityService.class));
        when(mapper.lockVersion("old", "user")).thenReturn(Map.of(
                "status", "CONFIRMED", "confirmed_facts_json", "{\"experienceMonths\":0}"));
        when(profiles.locked("user")).thenReturn(Map.of("profile_version", 5L, "active_resume_version_id", "new"));
        assertThrows(BusinessException.class, () -> service.correctFacts("user", "old", Map.of(
                "expectedProfileVersion", 5, "reason", "修正经验", "facts", Map.of("experienceMonths", 12))));
        verify(mapper, never()).correctFacts(anyString(), anyString());
    }
}
