package com.aijob.backend.project;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.job.JobMapper;
import com.aijob.backend.job.JobService;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.task.AiTask;
import com.aijob.backend.task.IdempotencyService;
import com.aijob.backend.task.TaskService;

import tools.jackson.databind.json.JsonMapper;

class ProjectResultHandlerTest {

    private final JsonService json = new JsonService(JsonMapper.builder().build());
    private final ProjectMapper mapper = mock(ProjectMapper.class);
    private final ProjectResultHandler handler = new ProjectResultHandler(mapper, json);

    @Test
    void recommendPersistsRepositorySourcesAndPlatformRuns() {
        AiTask task = task("PROJECT_RECOMMEND", "recommendation");
        task.setInputSnapshotJson(json.stringify(Map.of(
                "allowedSkillCodes", List.of("JAVA"),
                "difficulty", "BEGINNER",
                "maxDurationWeeks", 4)));
        when(mapper.lockRecommendation("recommendation", "user")).thenReturn(recommendation());
        when(mapper.completeRecommendation(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);

        handler.success(task, json.parse("""
                {"sourceRuns":[
                  {"platform":"GITHUB","status":"SUCCEEDED","reason":null},
                  {"platform":"GITEE","status":"FAILED","reason":"Gitee 检索被限流"}],
                 "items":[{
                   "title":"demo","summary":"A real Java repository.",
                   "skillCodes":["JAVA"],"difficulty":"BEGINNER","estimatedWeeks":2,
                   "reason":"适合练习 Java 基础。","gettingStarted":["阅读 README","运行示例"],
                   "deliverables":["运行说明","自测记录"],
                   "primarySource":{"platform":"GITHUB","path":"owner/demo",
                     "url":"https://github.com/owner/demo","license":"MIT",
                     "readmeUrl":"https://github.com/owner/demo/blob/main/README.md"},
                   "alternateSources":[{"platform":"GITEE","path":"owner/demo",
                     "url":"https://gitee.com/owner/demo","license":"MIT","readmeUrl":null}]}]}
                """));

        verify(mapper).insertRecommendationItem(anyString(), eq("recommendation"), eq("user"), eq("demo"),
                anyString(), contains("JAVA"), eq("BEGINNER"), eq(2), anyString(), contains("阅读 README"),
                contains("GITHUB"), contains("GITEE"), contains("自测记录"));
        verify(mapper).completeRecommendation(eq("recommendation"), eq("READY"), eq("task-1"),
                contains("owner/demo"), contains("GITEE"));
    }

    @Test
    void recommendPersistsGitCodeNestedRepositoryAndReadme() {
        AiTask task = task("PROJECT_RECOMMEND", "recommendation");
        task.setInputSnapshotJson(json.stringify(Map.of(
                "allowedSkillCodes", List.of("JAVA"), "difficulty", "BEGINNER", "maxDurationWeeks", 4)));
        when(mapper.lockRecommendation("recommendation", "user")).thenReturn(recommendation());
        when(mapper.completeRecommendation(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);

        String url = "https://gitcode.com/gh_mirrors/al/allure2";
        String readmeUrl = url + "/blob/main/README.md";
        handler.success(task, json.parse(resultWithSource("GITCODE", "gh_mirrors/al/allure2", url, readmeUrl)));

        verify(mapper).insertRecommendationItem(anyString(), eq("recommendation"), eq("user"), anyString(),
                anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), contains(readmeUrl),
                anyString(), anyString());
    }

    @Test
    void recommendRejectsUrlThatDoesNotMatchPlatformAndPath() {
        AiTask task = task("PROJECT_RECOMMEND", "recommendation");
        task.setInputSnapshotJson(json.stringify(Map.of(
                "allowedSkillCodes", List.of("JAVA"),
                "difficulty", "BEGINNER",
                "maxDurationWeeks", 4)));
        when(mapper.lockRecommendation("recommendation", "user")).thenReturn(recommendation());

        BusinessException wrongHost = assertThrows(BusinessException.class,
                () -> handler.success(task, json.parse(resultWithSource("GITHUB", "owner/demo",
                        "https://gitee.com/owner/demo", null))));
        assertEquals("AI_SCHEMA_INVALID", wrongHost.getErrorKey());

        BusinessException wrongPath = assertThrows(BusinessException.class,
                () -> handler.success(task, json.parse(resultWithSource("GITHUB", "owner/demo",
                        "https://github.com/owner/other", null))));
        assertEquals("AI_SCHEMA_INVALID", wrongPath.getErrorKey());

        BusinessException wrongReadme = assertThrows(BusinessException.class,
                () -> handler.success(task, json.parse(resultWithSource("GITHUB", "owner/demo",
                        "https://github.com/owner/demo",
                        "https://github.com/owner/other/blob/main/README.md"))));
        assertEquals("AI_SCHEMA_INVALID", wrongReadme.getErrorKey());

        verify(mapper, never()).insertRecommendationItem(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    void recommendAcceptsZeroItemsWhenPlatformsFailed() {
        AiTask task = task("PROJECT_RECOMMEND", "recommendation");
        task.setInputSnapshotJson(json.stringify(Map.of(
                "allowedSkillCodes", List.of("JAVA"),
                "maxDurationWeeks", 4)));
        when(mapper.lockRecommendation("recommendation", "user")).thenReturn(recommendation());
        when(mapper.completeRecommendation(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);

        handler.success(task, json.parse("""
                {"sourceRuns":[{"platform":"GITHUB","status":"FAILED","reason":"限流"},
                               {"platform":"GITEE","status":"FAILED","reason":"超时"}],
                 "items":[]}
                """));

        verify(mapper).completeRecommendation(eq("recommendation"), eq("READY"), eq("task-1"), eq("[]"),
                contains("FAILED"));
    }

    @Test
    void recommendationReadKeepsLegacyModeAndReturnsRepositorySources() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        ProjectService service = service(projectMapper);

        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("id", "legacy");
        legacy.put("status", "READY");
        legacy.put("items_json", "[]");
        when(projectMapper.recommendation("legacy", "user")).thenReturn(legacy);
        when(projectMapper.recommendationItems("legacy")).thenReturn(List.of());

        Map<String, Object> legacyView = service.recommendation("user", "legacy");
        assertEquals("LEGACY_IDEA", legacyView.get("mode"));
        assertEquals(List.of(), legacyView.get("sourceRuns"));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "new");
        row.put("status", "READY");
        row.put("mode", "REPOSITORY");
        row.put("items_json", "[]");
        row.put("source_runs_json", "[{\"platform\":\"GITHUB\",\"status\":\"SUCCEEDED\"}]");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "item");
        item.put("title", "demo");
        item.put("primary_source_json",
                "{\"platform\":\"GITHUB\",\"path\":\"owner/demo\",\"url\":\"https://github.com/owner/demo\","
                        + "\"license\":\"MIT\",\"readmeUrl\":null}");
        item.put("alternate_sources_json", "[]");
        item.put("getting_started_json", "[\"阅读 README\"]");
        when(projectMapper.recommendation("new", "user")).thenReturn(row);
        when(projectMapper.recommendationItems("new")).thenReturn(List.of(item));

        Map<String, Object> view = service.recommendation("user", "new");
        assertEquals("REPOSITORY", view.get("mode"));
        assertTrue(view.get("sourceRuns") instanceof List<?> runs && !runs.isEmpty());
        @SuppressWarnings("unchecked")
        Map<String, Object> primary = (Map<String, Object>) ((List<?>) view.get("items")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) primary.get("primarySource");
        assertEquals("GITHUB", source.get("platform"));
        assertEquals("owner/demo", source.get("path"));
        assertEquals("MIT", source.get("license"));
    }

    @Test
void legacyCandidateWithoutListsStillBuildsPlanPayload() {
    ProjectMapper projectMapper = mock(ProjectMapper.class);
    TaskService tasks = mock(TaskService.class);
    ProjectService service = new ProjectService(projectMapper, mock(JobMapper.class), mock(JobService.class),
            mock(ProfileService.class), tasks, mock(IdempotencyService.class),
            json, new ApiView(json), new ProjectProgressService());

    String itemId = "20000000-0000-4000-8000-000000000001";
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", itemId);
    item.put("title", "历史项目");
    item.put("summary", "历史练习建议");
    item.put("skill_codes_json", "[\"JAVA\"]");
    item.put("difficulty", "INTERMEDIATE");
    item.put("estimated_weeks", 2);
    item.put("reason", "历史推荐");
    item.put("deliverables_json", "[\"历史交付\"]");
    item.put("getting_started_json", null);
    item.put("primary_source_json", null);
    item.put("alternate_sources_json", null);
    item.put("recommendation_status", "READY");
    item.put("profile_version", 3);
    when(projectMapper.recommendationItem(itemId, "user")).thenReturn(item);

    service.createPlan("user", "/api/v1/projects/plans", "key",
            Map.<String, Object>of("recommendationItemId", itemId, "weeklyHours", 4));

    @SuppressWarnings("unchecked")
    org.mockito.ArgumentCaptor<Map<String, Object>> payload =
            org.mockito.ArgumentCaptor.forClass(Map.class);
    verify(tasks).create(anyString(), anyString(), anyString(), anyString(), any(), anyString(), anyString(),
            anyString(), anyMap(), payload.capture(), any());
    @SuppressWarnings("unchecked")
    Map<String, Object> recommendation = (Map<String, Object>) payload.getValue().get("recommendation");
    assertEquals(List.of(), recommendation.get("gettingStarted"));
    assertEquals(List.of(), recommendation.get("alternateSources"));
    assertEquals(null, recommendation.get("primarySource"));
}

private ProjectService service(ProjectMapper projectMapper) {
        return new ProjectService(projectMapper, mock(JobMapper.class), mock(JobService.class),
                mock(ProfileService.class), mock(TaskService.class), mock(IdempotencyService.class),
                json, new ApiView(json), new ProjectProgressService());
    }

    private String resultWithSource(String platform, String path, String url, String readmeUrl) {
        String readme = readmeUrl == null ? "null" : "\"" + readmeUrl + "\"";
        return """
                {"sourceRuns":[{"platform":"GITHUB","status":"SUCCEEDED"}],
                 "items":[{
                   "title":"demo","summary":"A real Java repository.",
                   "skillCodes":["JAVA"],"difficulty":"BEGINNER","estimatedWeeks":2,
                   "reason":"适合练习 Java 基础。","gettingStarted":["阅读 README"],
                   "deliverables":["运行说明"],
                   "primarySource":{"platform":"%s","path":"%s","url":"%s","license":"MIT","readmeUrl":%s},
                   "alternateSources":[]}]}
                """.formatted(platform, path, url, readme);
    }

    private Map<String, Object> recommendation() {
        Map<String, Object> recommendation = new LinkedHashMap<>();
        recommendation.put("id", "recommendation");
        recommendation.put("status", "GENERATING");
        return recommendation;
    }

    private AiTask task(String operation, String resourceId) {
        AiTask task = new AiTask();
        task.setId("task-1");
        task.setUserId("user");
        task.setOperation(operation);
        task.setResourceId(resourceId);
        return task;
    }
}
