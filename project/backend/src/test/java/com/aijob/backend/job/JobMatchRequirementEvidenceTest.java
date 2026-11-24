package com.aijob.backend.job;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aijob.backend.common.JsonService;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.task.AiTask;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class JobMatchRequirementEvidenceTest {

    private JobMapper mapper;
    private JsonService json;
    private EligibilityService eligibility;
    private MatchScoreService scores;
    private JobResultHandler handler;

    @BeforeEach
    void setUp() {
        mapper = mock(JobMapper.class);
        json = new JsonService(JsonMapper.builder().build());
        eligibility = mock(EligibilityService.class);
        scores = mock(MatchScoreService.class);
        handler = new JobResultHandler(mapper, mock(JobService.class), json, eligibility, scores,
                mock(ProfileService.class));
    }

    @Test
    void validatesQuotesAndKeepsPartialSupportEvenWithHighSkillSamples() {
        String jdText = "熟悉 Redis 缓存一致性方案的实现与排查，了解常见解决方案。";
        String resumeQuote = "使用 Redis 缓存商品信息";
        when(mapper.lockBatch("match", "user")).thenReturn(batch(jdText));
        when(eligibility.evaluate(any(), any())).thenReturn(
                Map.of("status", "PASS", "ruleVersion", "eligibility-v1", "checks", List.of()));
        when(scores.skill(any(), any())).thenReturn(BigDecimal.ONE);
        when(scores.preference(any(), any(), any(), any())).thenReturn(BigDecimal.ONE);
        when(scores.score(any(), any(), any())).thenReturn(80);
        when(scores.component(anyString(), any(), any(), anyString()))
                .thenReturn(Map.of("key", "skill", "value", 1, "weight", 0.6, "provenance", "confirmed-resume-v1"));
        when(mapper.insertItem(any(), any(), any(), anyInt(), anyInt(), any(), anyString(), any(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(1);

        AiTask task = task(jdText, resumeQuote);
        handler.success(task, json.tree(agentResult()));

        ArgumentCaptor<String> gapsJson = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sourceJson = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertItem(any(), eq("match"), eq("job"), eq(1), anyInt(), eq("PASS"), anyString(),
                any(), anyString(), gapsJson.capture(), anyString(), sourceJson.capture());

        JsonNode gaps = json.parse(gapsJson.getValue());
        assertEquals(90, gaps.get(0).path("currentScore").asInt());
        assertEquals("ev-submitted", gaps.get(0).path("evidenceIds").get(0).asText());

        JsonNode snapshot = json.parse(sourceJson.getValue());
        JsonNode cards = snapshot.path("requirementEvidence");
        assertEquals(2, cards.size());
        assertEquals("PARTIAL", cards.get(0).path("supportLevel").asText());
        assertEquals("使用 Redis 缓存商品信息", cards.get(0).path("resumeQuote").asText());
        assertEquals("redis", cards.get(0).path("skillCode").asText());
        assertEquals("MISSING", cards.get(1).path("supportLevel").asText());
        assertTrue(cards.get(1).path("resumeQuote").isNull());
        JsonNode coverage = snapshot.path("requirementEvidenceCoverage");
        assertEquals(2, coverage.path("returned").asInt());
        assertEquals(4000, coverage.path("jdInputLimit").asInt());
        assertEquals(false, coverage.path("jdTruncated").asBoolean());
    }

    @Test
    void doesNotExposeEvidenceCardsForPublicJobs() {
        String jdText = "熟悉 Redis 缓存一致性方案的实现与排查。";
        when(mapper.lockBatch("match", "user")).thenReturn(batch(jdText));
        when(eligibility.evaluate(any(), any())).thenReturn(
                Map.of("status", "PASS", "ruleVersion", "eligibility-v1", "checks", List.of()));
        when(scores.skill(any(), any())).thenReturn(BigDecimal.ONE);
        when(scores.preference(any(), any(), any(), any())).thenReturn(BigDecimal.ONE);
        when(scores.score(any(), any(), any())).thenReturn(80);
        when(scores.component(anyString(), any(), any(), anyString()))
                .thenReturn(Map.of("key", "skill", "value", 1, "weight", 0.6, "provenance", "confirmed-resume-v1"));
        when(mapper.insertItem(any(), any(), any(), anyInt(), anyInt(), any(), anyString(), any(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(1);

        AiTask task = task(jdText, "实习经历：使用 Redis 缓存商品信息。");
        Map<String, Object> payload = json.convert(json.parse(task.getInputSnapshotJson()), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) payload.get("candidates");
        candidates.get(0).put("personalOwned", false);
        task.setInputSnapshotJson(json.stringify(payload));

        handler.success(task, json.tree(agentResult()));

        ArgumentCaptor<String> sourceJson = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertItem(any(), eq("match"), eq("job"), eq(1), anyInt(), eq("PASS"), anyString(),
                any(), anyString(), anyString(), anyString(), sourceJson.capture());
        JsonNode snapshot = json.parse(sourceJson.getValue());
        assertEquals(0, snapshot.path("requirementEvidence").size());
        assertTrue(snapshot.path("requirementEvidenceCoverage").isNull());
    }

    @Test
    void explicitComparisonChecksSelectedResumeAndSavesPublicJobGaps() {
        String requirement = "熟悉 Redis 缓存一致性方案的实现与排查";
        String quote = "使用 Redis 缓存商品信息";
        when(mapper.lockBatch("match", "user")).thenReturn(batch(requirement));
        when(eligibility.evaluate(any(), any())).thenReturn(Map.of("status", "UNKNOWN"));
        when(scores.component(anyString(), any(), any(), anyString())).thenReturn(Map.of());
        AiTask task = task(requirement, "其他简历的引文");
        var payload = json.map(task.getInputSnapshotJson());
        payload.put("resumeText", "实习经历：" + quote + "。");
        payload.put("candidates", List.of(Map.of("jobId", "job", "jobVersion", 1,
                "personalOwned", false, "requirements", Map.of("skills", List.of("redis")))));
        task.setInputSnapshotJson(json.stringify(payload));
        var card = new LinkedHashMap<String, Object>(Map.of("requirementQuote", requirement, "resumeQuote", quote,
                "supportLevel", "PARTIAL", "gapTitle", "缺少一致性案例", "gapSummary", "尚未说明缓存失效策略。"));
        var response = Map.of("items", List.of(Map.of("jobId", "job", "jobVersion", 1, "requirementEvidence", List.of(card))));
        handler.success(task, json.tree(response));

        var snapshot = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertItem(any(), eq("match"), eq("job"), eq(1), anyInt(), eq("UNKNOWN"), anyString(),
                any(), anyString(), anyString(), anyString(), snapshot.capture());
        var saved = json.parse(snapshot.getValue()).path("requirementEvidence").get(0);
        assertEquals(quote, saved.path("resumeQuote").asText());
        assertEquals("缺少一致性案例", saved.path("gapTitle").asText());
        card.put("resumeQuote", "其他简历的引文");
        assertThrows(BusinessException.class, () -> handler.success(task, json.tree(response)));
    }

    private AiTask task(String jdText, String resumeQuote) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("profile", Map.of(
                "targetRole", "JAVA_BACKEND",
                "cities", List.of("330100"),
                "facts", Map.of(
                        "education", "BACHELOR",
                        "graduationYear", 2027,
                        "experienceMonths", 0,
                        "skills", List.of(Map.of("skillCode", "redis")))));
        payload.put("skillSnapshot", List.of(Map.of("skillCode", "redis", "score", 90, "sampleCount", 5)));
        payload.put("candidates", List.of(Map.of(
                "jobId", "job",
                "jobVersion", 1,
                "personalOwned", true,
                "requirements", Map.of("skills", List.of("redis"),
                        "requiredSkillLevels", Map.of("redis", 60)))));
        payload.put("resumeQuotes", List.of(resumeQuote));
        AiTask task = new AiTask();
        task.setId("task");
        task.setUserId("user");
        task.setResourceId("match");
        task.setOperation("JOB_MATCH");
        task.setInputSnapshotJson(json.stringify(payload));
        return task;
    }

    private Map<String, Object> batch(String jdText) {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", "job");
        job.put("title", "Java 后端开发");
        job.put("companyName", "虚构星河科技");
        job.put("cityCode", "330100");
        job.put("roleCode", "JAVA_BACKEND");
        job.put("jdText", jdText);
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("id", "match");
        batch.put("status", "GENERATING");
        batch.put("user_id", "user");
        batch.put("input_snapshot_json", json.stringify(Map.of("includeUnknown", true, "jobs", List.of(job),
                "scoreEvidenceIds", Map.of("redis", List.of("ev-submitted")))));
        return batch;
    }

    private Map<String, Object> agentResult() {
        Map<String, Object> card1 = new LinkedHashMap<>();
        card1.put("requirementQuote", "熟悉 Redis 缓存一致性方案的实现与排查");
        card1.put("skillCode", "redis");
        card1.put("supportLevel", "PARTIAL");
        card1.put("resumeQuote", "使用 Redis 缓存商品信息");
        Map<String, Object> card2 = new LinkedHashMap<>();
        card2.put("requirementQuote", "熟悉Redis缓存一致性方案的实现与排查");
        card2.put("skillCode", "redis");
        card2.put("supportLevel", "DIRECT");
        card2.put("resumeQuote", "使用 Redis 缓存商品信息");
        Map<String, Object> card3 = new LinkedHashMap<>();
        card3.put("requirementQuote", "熟悉 Redis 缓存一致性方案的实现与排查");
        card3.put("skillCode", "redis");
        card3.put("supportLevel", "DIRECT");
        card3.put("resumeQuote", "使用Redis缓存商品信息");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("jobId", "job");
        item.put("jobVersion", 1);
        item.put("semanticScore", 0.8);
        item.put("reasons", List.of("方向一致"));
        item.put("gaps", List.of());
        item.put("requirementEvidence", List.of(card1, card2, card3));
        return Map.of("items", List.of(item));
    }
}
