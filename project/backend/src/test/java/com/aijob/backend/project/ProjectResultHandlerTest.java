package com.aijob.backend.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.task.AiTask;

import tools.jackson.databind.json.JsonMapper;

class ProjectResultHandlerTest {
    private final ProjectMapper mapper = mock(ProjectMapper.class);
    private final JsonService json = new JsonService(JsonMapper.builder().build());
    private final ProjectResultHandler handler = new ProjectResultHandler(mapper, json);
    private final List<String> targets = List.of("DOCKER", "FASTAPI", "JAVA", "REDIS", "MYSQL");

    @Test
    void savesFrozenTargetsWhenDifferentRepositoriesTogetherCoverAllSkills() {
        AiTask task = task();
        when(mapper.completeRecommendation(eq("recommendation"), eq("READY"), eq("task"), anyString())).thenReturn(1);
        Map<String, Object> mall = githubItem("mall", List.of("DOCKER", "JAVA", "REDIS", "MYSQL"));
        Map<String, Object> api = githubItem("api", List.of("DOCKER", "FASTAPI", "REDIS"));
        assertEquals(1, handler.success(task, json.tree(Map.of("items", List.of(mall, api)))));
        ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
        verify(mapper).completeRecommendation(eq("recommendation"), eq("READY"), eq("task"), snapshot.capture());
        assertEquals(targets, json.map(snapshot.getValue()).get("targetSkillCodes"));
        assertEquals(2, ((List<?>) json.map(snapshot.getValue()).get("items")).size());
    }

    @Test
    void rejectsResultWhoseUnionStillMissesFastapi() {
        AiTask task = task();
        BusinessException error = assertThrows(BusinessException.class, () -> handler.success(task,
                json.tree(Map.of("items", List.of(item(List.of("DOCKER", "JAVA", "REDIS", "MYSQL")))))));
        assertEquals("推荐候选集合未覆盖全部目标技能", error.getMessage());
    }

    @Test
    void rejectsUnmatchedSkillAddedToGithubRepository() {
        AiTask task = task();
        Map<String, Object> forged = item(targets);
        forged.put("sourceType", "GITHUB_REPOSITORY");
        forged.put("source", "GITHUB_API");
        forged.put("repository", Map.of("fullName", "example/mall", "htmlUrl", "https://github.com/example/mall",
                "description", "Java service", "stargazersCount", 100,
                "pushedAt", "2026-01-01T00:00:00Z", "topics", List.of("java"),
                "matchedSkillCodes", List.of("DOCKER", "JAVA", "REDIS", "MYSQL"),
                "source", "GITHUB_API", "verifiedAt", "2026-11-18T00:00:00Z"));
        BusinessException error = assertThrows(BusinessException.class,
                () -> handler.success(task, json.tree(Map.of("items", List.of(forged)))));
        assertEquals("真实仓库技能必须与实际匹配的目标技能一致", error.getMessage());
    }

    private AiTask task() {
        AiTask task = new AiTask();
        task.setId("task");
        task.setUserId("user");
        task.setResourceId("recommendation");
        task.setOperation("PROJECT_RECOMMEND");
        task.setInputSnapshotJson(json.stringify(Map.of("allowedSkillCodes", targets,
                "difficulty", "INTERMEDIATE", "maxDurationWeeks", 7)));
        when(mapper.lockRecommendation("recommendation", "user")).thenReturn(Map.of("status", "GENERATING"));
        return task;
    }

    private Map<String, Object> item(List<String> skills) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "综合练习");
        result.put("summary", "实现综合业务流程");
        result.put("skillCodes", skills);
        result.put("difficulty", "INTERMEDIATE");
        result.put("estimatedWeeks", 7);
        result.put("reason", "练习目标技能");
        result.put("deliverables", List.of("源码和自测记录"));
        result.put("sourceType", "AI_GENERATED");
        result.put("source", "MODEL");
        return result;
    }

    private Map<String, Object> githubItem(String name, List<String> skills) {
        Map<String, Object> result = item(skills);
        result.put("sourceType", "GITHUB_REPOSITORY");
        result.put("source", "GITHUB_API");
        result.put("repository", Map.of("fullName", "example/" + name,
                "htmlUrl", "https://github.com/example/" + name, "description", "Service project",
                "stargazersCount", 100, "pushedAt", "2026-01-01T00:00:00Z", "topics", List.of("docker"),
                "matchedSkillCodes", skills.stream().map(String::toLowerCase).toList(),
                "source", "GITHUB_API", "verifiedAt", "2026-11-18T00:00:00Z"));
        return result;
    }
}
