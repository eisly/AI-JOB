package com.aijob.backend.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aijob.backend.ability.AbilityService;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.task.AiTask;

import tools.jackson.databind.ObjectMapper;

class JobSearchResultHandlerTest {

    @Test
    void filtersAndRanksTheCompleteBatchBeforeApplyingTheLimit() {
        JsonService json = new JsonService(new ObjectMapper());
        JobSearchMapper mapper = mock(JobSearchMapper.class);
        JobMapper jobs = mock(JobMapper.class);
        JobService jobService = mock(JobService.class);
        AbilityService ability = mock(AbilityService.class);
        ProfileService profiles = mock(ProfileService.class);
        when(jobs.lockBatch("search", "user")).thenReturn(Map.of("status", "GENERATING"));
        when(mapper.sourceId("web")).thenReturn("source");
        when(ability.snapshots("user")).thenReturn(List.of());
        when(profiles.agentSkillCatalog()).thenReturn(List.of());
        when(jobService.roleCode(anyString())).thenReturn("JAVA_BACKEND");

        List<List<Object>> ranked = new ArrayList<>();
        doAnswer(call -> {
            Map<String, Object> snapshot = json.map((String) call.getArgument(11));
            ranked.add(List.of(call.getArgument(4), call.getArgument(5), snapshot.get("title"), call.getArgument(7)));
            return 1;
        }).when(jobs).insertItem(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), anyString());

        AiTask task = new AiTask();
        task.setId("task");
        task.setResourceId("search");
        task.setUserId("user");
        task.setInputSnapshotJson(json.stringify(Map.of(
                "profile", Map.of("targetRole", "JAVA_BACKEND", "facts", Map.of("education", "BACHELOR")),
                "filters", Map.of("cities", List.of("110100"), "maxResults", 2))));
        JobSearchResultHandler handler = new JobSearchResultHandler(mapper, jobs, jobService, json,
                new EligibilityService(), new MatchScoreService(), ability, profiles);
        handler.success(task, json.tree(Map.of("items", List.of(
                job("资格未知高分", "110100", null, 1, "2026-11-05T00:00:00Z"),
                job("较旧的资格通过", "110100", "BACHELOR", 0.1, "2026-11-02T00:00:00Z"),
                job("外地岗位", "310100", "BACHELOR", 1, "2026-11-06T00:00:00Z"),
                job("较新的资格通过", "110100", "BACHELOR", 0.1, "2026-11-03T00:00:00Z")))));

        assertEquals(List.of(List.of(1, "PASS", "较新的资格通过", 10), List.of(2, "PASS", "较旧的资格通过", 10)), ranked);
    }

    private Map<String, Object> job(String title, String city, String education, double score, String publishedAt) {
        String quote = "要求本科及以上学历";
        return Map.of(
                "sourceKey", "web", "sourceUrl", "https://careers.example.com/jobs/" + title.hashCode(),
                "title", title, "companyName", "示例公司", "cityCode", city, "candidateType", "EXPERIENCED",
                "jdText", education == null ? "负责 Java 后端开发" : quote,
                "requirements", education == null ? Map.of()
                        : Map.of("educationMin", education, "quotes", Map.of("educationMin", quote)),
                "semanticScore", score, "publishedAt", publishedAt);
    }
}
