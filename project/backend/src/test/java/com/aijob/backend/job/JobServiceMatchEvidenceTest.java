package com.aijob.backend.job;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aijob.backend.ability.AbilityService;
import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.resume.ResumeMapper;
import com.aijob.backend.task.TaskService;

import tools.jackson.databind.json.JsonMapper;

class JobServiceMatchEvidenceTest {

    private JobMapper mapper;
    private AbilityService ability;
    private JobService service;
    private JsonService json;

    @BeforeEach
    void setUp() {
        mapper = mock(JobMapper.class);
        ability = mock(AbilityService.class);
        json = new JsonService(JsonMapper.builder().build());
        service = new JobService(mapper, mock(ResumeMapper.class), mock(ProfileService.class),
                mock(TaskService.class), json, new ApiView(json), ability);
    }

    @Test
    void rejectsItemFromAnotherBatchOrUser() {
        when(mapper.matchItem("item", "user")).thenReturn(evidenceRow("match", List.of("redis", "ev1")));

        assertThrows(BusinessException.class,
                () -> service.matchEvidence("user", "other-match", "item", "redis"));
    }

    @Test
    void rejectsSkillOutsideMatchItemGaps() {
        when(mapper.matchItem("item", "user")).thenReturn(evidenceRow("match", List.of("redis", "ev1")));

        assertThrows(BusinessException.class,
                () -> service.matchEvidence("user", "match", "item", "mysql"));
    }

    @Test
    void returnsEmptyReasonWhenSkillHasNoSample() {
        when(mapper.matchItem("item", "user")).thenReturn(evidenceRow("match", List.of("redis")));

        Map<String, Object> result = service.matchEvidence("user", "match", "item", "redis");

        assertEquals("redis", result.get("skillCode"));
        assertEquals(List.of(), result.get("evidence"));
        assertTrue(String.valueOf(result.get("reason")).contains("暂无可用评分样本"));
    }

    @Test
    void returnsLearningEvidenceWithQuestionAndAnswerExcerpt() {
        when(mapper.matchItem("item", "user")).thenReturn(evidenceRow("match", List.of("redis", "ev1", "ev2")));
        when(ability.evidenceByIds(eq("user"), anyList())).thenReturn(List.of(
                row("ev1", "LEARNING_ANSWER", "answer-1", 85, "VALID"),
                row("ev2", "LEARNING_ANSWER", "answer-2", 40, "REVOKED")));
        when(ability.learningAnswerDetails(anyList())).thenReturn(List.of(
                Map.of("id", "answer-1", "content", "我使用 Redis 缓存了商品详情，并处理更新一致性。",
                        "score", 85, "stem", "如何处理 Redis 缓存一致性？")));

        Map<String, Object> result = service.matchEvidence("user", "match", "item", "redis");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) result.get("evidence");

        assertEquals(2, evidence.size());
        assertEquals("LEARNING_ANSWER", evidence.get(0).get("sourceType"));
        assertEquals(85, evidence.get(0).get("score"));
        assertEquals("如何处理 Redis 缓存一致性？", evidence.get(0).get("question"));
        assertTrue(String.valueOf(evidence.get(0).get("answerExcerpt")).contains("Redis"));
        assertEquals(true, evidence.get(0).get("available"));
        assertEquals(false, evidence.get(1).get("available"));
        assertTrue(String.valueOf(evidence.get(1).get("reason")).contains("撤销"));
    }

    private Map<String, Object> evidenceRow(String batchId, List<String> evidenceIds) {
        Map<String, Object> gap = new LinkedHashMap<>();
        gap.put("skillCode", "redis");
        gap.put("evidenceIds", evidenceIds.size() > 1 ? evidenceIds.subList(1, evidenceIds.size()) : List.of());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "item");
        item.put("batch_id", batchId);
        item.put("gaps_json", json.stringify(List.of(gap)));
        return item;
    }

    private Map<String, Object> row(String id, String type, String sourceId, int score, String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("source_type", type);
        row.put("source_id", sourceId);
        row.put("score", score);
        row.put("status", status);
        row.put("created_at", Instant.now());
        return row;
    }
}