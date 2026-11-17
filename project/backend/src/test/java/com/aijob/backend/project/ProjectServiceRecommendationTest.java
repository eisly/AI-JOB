package com.aijob.backend.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.JsonService;

import tools.jackson.databind.json.JsonMapper;

class ProjectServiceRecommendationTest {

    private final ProjectMapper mapper = mock(ProjectMapper.class);
    private final JsonService json = new JsonService(JsonMapper.builder().build());
    private final ProjectService service = new ProjectService(mapper, null, null, null, null, null,
            json, new ApiView(json), new ProjectProgressService());

    @Test
    void ranksCombinedRepositoriesAheadOfPopularSingleSkillRepositoriesAfterDatabaseRead() {
        when(mapper.recommendation("recommendation", "user"))
                .thenReturn(Map.of("id", "recommendation", "status", "READY"));
        when(mapper.recommendationItems("recommendation")).thenReturn(List.of(
                row("popular-docker", List.of("DOCKER"), 50000),
                row("combined", List.of("DOCKER", "FASTAPI", "PYTHON"), 100),
                row("combined-popular", List.of("DOCKER", "FASTAPI", "PYTHON"), 200),
                row("ai-practice", List.of("DOCKER", "FASTAPI", "PYTHON", "GO", "REDIS"), null)));

        Map<String, Object> result = service.recommendation("user", "recommendation");
        List<?> items = (List<?>) result.get("items");
        assertEquals(List.of("combined-popular", "combined", "popular-docker", "ai-practice"),
                items.stream().map(item -> ((Map<?, ?>) item).get("id")).toList());
        assertEquals(List.of("DOCKER", "FASTAPI", "PYTHON"), ((Map<?, ?>) items.getFirst()).get("skillCodes"));
    }

    @Test
    void acceptsLegacyAiItemsWithoutRepositoryMetadata() {
        when(mapper.recommendation("recommendation", "user"))
                .thenReturn(Map.of("id", "recommendation", "status", "READY"));
        when(mapper.recommendationItems("recommendation"))
                .thenReturn(List.of(Map.of("id", "legacy", "skill_codes_json", "[\"JAVA\"]")));

        List<?> items = (List<?>) service.recommendation("user", "recommendation").get("items");
        assertEquals("legacy", ((Map<?, ?>) items.getFirst()).get("id"));
    }

    @Test
    void reportsFrozenTargetsAndDistinguishesAiCoverageFromGithubCoverage() {
        List<String> targets = List.of("DOCKER", "FASTAPI", "JAVA", "REDIS", "MYSQL");
        when(mapper.recommendation("recommendation", "user")).thenReturn(Map.of(
                "id", "recommendation", "status", "READY", "items_json",
                json.stringify(Map.of("targetSkillCodes", targets, "items", List.of()))));
        when(mapper.recommendationItems("recommendation")).thenReturn(List.of(
                row("mall", List.of("DOCKER", "JAVA", "REDIS", "MYSQL"), 1000),
                row("ai-api", List.of("FASTAPI"), null)));

        Map<String, Object> result = service.recommendation("user", "recommendation");
        assertEquals(targets, result.get("targetSkillCodes"));
        assertEquals(targets, result.get("coveredSkillCodes"));
        assertEquals(List.of("DOCKER", "JAVA", "REDIS", "MYSQL"), result.get("githubCoveredSkillCodes"));
        assertEquals(List.of(), result.get("missingSkillCodes"));
        List<?> items = (List<?>) result.get("items");
        assertEquals(List.of("FASTAPI"), ((Map<?, ?>) items.getFirst()).get("missingSkillCodes"));
        assertEquals(List.of("DOCKER", "JAVA", "REDIS", "MYSQL"), ((Map<?, ?>) items.getLast()).get("missingSkillCodes"));
    }

    @Test
    void legacyArraySnapshotDoesNotInventOriginalTargets() {
        when(mapper.recommendation("recommendation", "user")).thenReturn(Map.of(
                "id", "recommendation", "status", "READY", "items_json", "[]"));
        when(mapper.recommendationItems("recommendation")).thenReturn(List.of(row("legacy", List.of("JAVA"), null)));
        assertEquals(List.of(), service.recommendation("user", "recommendation").get("targetSkillCodes"));
    }

    private Map<String, Object> row(String id, List<String> skills, Integer stars) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("skill_codes_json", json.stringify(skills));
        row.put("source_type", stars == null ? "AI_GENERATED" : "GITHUB_REPOSITORY");
        row.put("recommendation_source", stars == null ? "MODEL" : "GITHUB_API");
        row.put("repository_json", stars == null ? null : json.stringify(Map.of("stargazersCount", stars)));
        return row;
    }
}
