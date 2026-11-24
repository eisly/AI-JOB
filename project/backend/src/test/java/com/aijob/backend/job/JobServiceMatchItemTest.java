package com.aijob.backend.job;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aijob.backend.ability.AbilityService;
import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.resume.ResumeMapper;
import com.aijob.backend.task.TaskService;

import tools.jackson.databind.ObjectMapper;

class JobServiceMatchItemTest {

    @Test
    void matchItemExposesCompanyNameFromSnapshot() {
        JsonService json = new JsonService(new ObjectMapper());
        JobService service = new JobService(mock(JobMapper.class), mock(ResumeMapper.class),
                mock(ProfileService.class), mock(TaskService.class), json, new ApiView(json),
                mock(AbilityService.class));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("source_snapshot_json", "{\"title\":\"Java 后端开发\",\"companyName\":\"虚构海盐研发中心\","
                + "\"cityCode\":\"330100\",\"candidateType\":\"CAMPUS\"}");

        Map<String, Object> result = service.matchItemVo(item);

        assertEquals("虚构海盐研发中心", result.get("companyName"));
        assertEquals("330100", result.get("cityCode"));
        assertEquals("CAMPUS", result.get("candidateType"));
    }

    @Test
    void personalJdWithFailedEligibilityRemainsVisibleInSavedBatch() {
        JsonService json = new JsonService(new ObjectMapper());
        JobMapper jobs = mock(JobMapper.class);
        ProfileService profiles = mock(ProfileService.class);
        JobService service = new JobService(jobs, mock(ResumeMapper.class), profiles,
                mock(TaskService.class), json, new ApiView(json), mock(AbilityService.class));
        var cards = List.of(Map.of("requirementQuote", "具备 Java 开发经验", "supportLevel", "MISSING"));
        var failedPersonal = Map.<String, Object>of("id", "personal", "eligibility_status", "FAIL",
                "eligibility_json", "{\"status\":\"FAIL\"}",
                "source_snapshot_json", json.stringify(Map.of("source", Map.of("type", "USER_INPUT"), "requirementEvidence", cards)));
        var failedPublic = Map.<String, Object>of("id", "public", "eligibility_status", "FAIL",
                "source_snapshot_json", "{\"source\":{\"type\":\"OFFICIAL\"}}");
        var unknown = Map.<String, Object>of("id", "unknown", "eligibility_status", "UNKNOWN", "source_snapshot_json", "{}");
        when(profiles.get("user")).thenReturn(Map.of("profileVersion", 3));
        when(jobs.items("match")).thenReturn(List.of(failedPersonal, failedPublic, unknown));
        when(jobs.batch("match", "user")).thenReturn(Map.of("id", "match", "user_id", "user", "profile_version", 3,
                "status", "READY", "input_snapshot_json", "{\"includeUnknown\":false}"));

        var result = json.tree(service.match("user", "match")).path("items");

        assertEquals(1, result.size());
        assertEquals("personal", result.get(0).path("id").asText());
        assertEquals("FAIL", result.get(0).path("eligibility").path("status").asText());
        assertEquals("具备 Java 开发经验", result.get(0).path("requirementEvidence").get(0).path("requirementQuote").asText());

        when(jobs.batch("match", "user")).thenReturn(Map.of("id", "match", "user_id", "user", "profile_version", 3,
                "resume_version_id", "selected-resume", "status", "READY", "input_snapshot_json",
                "{\"includeUnknown\":false,\"resumeName\":\"后端简历\",\"resumeVersionNo\":2,\"jobs\":[{\"title\":\"Java 后端开发\"}]}"));
        when(jobs.items("match")).thenReturn(List.of(failedPublic));
        var comparison = json.tree(service.match("user", "match"));
        assertEquals("selected-resume", comparison.path("resumeVersionId").asText());
        assertEquals("Java 后端开发", comparison.path("targetTitle").asText());
        assertEquals("public", comparison.path("items").get(0).path("id").asText());
    }
}
