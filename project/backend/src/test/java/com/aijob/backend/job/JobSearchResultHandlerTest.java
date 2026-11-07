package com.aijob.backend.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aijob.backend.ability.AbilityService;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.task.AiTask;

import tools.jackson.databind.ObjectMapper;

class JobSearchResultHandlerTest {

    private record Ranked(int rank, String eligibility, String title, int score, String legacyStatus,
                          String verificationStatus, String reason) {
    }

    @Test
    void filtersAndRanksTheCompleteBatchBeforeApplyingTheLimit() {
        Harness harness = new Harness(6);
        Map<String, Object> legacy = harness.job("无在招证据的高分岗位", "110100", "BACHELOR", 1, "2026-11-07T00:00:00Z");
        legacy.remove("verificationStatus");
        legacy.remove("verificationEvidence");
        legacy.remove("verificationCheckedAt");
        legacy.remove("recruitmentEvidence");
        Map<String, Object> stale = harness.job("核验已过时的高分岗位", "110100", "BACHELOR", 1, "2026-11-06T00:00:00Z");
        stale.put("recruitmentCheckedAt", "2020-01-01T00:00:00Z");
        stale.put("verificationCheckedAt", "2020-01-01T00:00:00Z");
        Map<String, Object> closed = harness.job("原文显示已下线的相关岗位", "110100", "BACHELOR", 0.2, "2026-11-06T00:00:00Z");
        closed.put("recruitmentStatus", "CLOSED");
        closed.put("verificationStatus", "CLOSED");
        closed.put("verificationReason", "OFFICIAL_CLOSED");
        closed.put("verificationEvidence", "OFFICIAL_CLOSED");
        harness.run(List.of(
                legacy, stale, closed,
                harness.job("资格未知高分", "110100", null, 1, "2026-11-05T00:00:00Z"),
                harness.job("较旧的资格通过", "110100", "BACHELOR", 0.1, "2026-11-02T00:00:00Z"),
                harness.job("外地岗位", "310100", "BACHELOR", 1, "2026-11-06T00:00:00Z"),
                harness.job("较新的资格通过", "110100", "BACHELOR", 0.1, "2026-11-03T00:00:00Z")));

        assertEquals(List.of(
                new Ranked(1, "PASS", "无在招证据的高分岗位", 100, "UNKNOWN", "UNKNOWN", "INSUFFICIENT_EVIDENCE"),
                new Ranked(2, "PASS", "核验已过时的高分岗位", 100, "UNKNOWN", "UNKNOWN", "INSUFFICIENT_EVIDENCE"),
                new Ranked(3, "PASS", "较新的资格通过", 10, "OPEN", "ACTIVE_CONFIRMED", "ACTIVE_EVIDENCE_FOUND"),
                new Ranked(4, "PASS", "较旧的资格通过", 10, "OPEN", "ACTIVE_CONFIRMED", "ACTIVE_EVIDENCE_FOUND"),
                new Ranked(5, "UNKNOWN", "资格未知高分", 100, "OPEN", "ACTIVE_CONFIRMED", "ACTIVE_EVIDENCE_FOUND"),
                new Ranked(6, "PASS", "原文显示已下线的相关岗位", 20, "CLOSED", "CLOSED", "OFFICIAL_CLOSED")),
                harness.ranked);
    }

    @Test
    void unknownAndProbableAreKeptAndDistinguished() {
        Harness harness = new Harness(10);
        Map<String, Object> confirmed = harness.job("已确认在招", "110100", "BACHELOR", 0.5, "2026-11-06T00:00:00Z");
        Map<String, Object> probable = harness.job("待确认在招", "110100", "BACHELOR", 0.5, "2026-11-06T00:00:00Z");
        probable.put("verificationStatus", "ACTIVE_PROBABLE");
        probable.put("verificationEvidence", "DETAIL_TEXT");
        probable.put("verificationReason", "APPLY_LINK_MISSING");
        Map<String, Object> blocked = harness.job("访问受限", "110100", "BACHELOR", 0.5, "2026-11-06T00:00:00Z");
        blocked.put("verificationStatus", "UNKNOWN");
        blocked.put("verificationReason", "HTTP_403");
        blocked.put("verificationEvidence", null);
        blocked.put("recruitmentEvidence", null);

        harness.run(List.of(confirmed, probable, blocked));

        assertEquals(List.of("ACTIVE_CONFIRMED", "ACTIVE_PROBABLE", "UNKNOWN"),
                harness.ranked.stream().map(Ranked::verificationStatus).toList());
        assertEquals("PROBABLE", harness.ranked.get(1).legacyStatus());
        assertEquals("UNKNOWN", harness.ranked.get(2).legacyStatus());
        assertTrue(harness.ranked.get(2).reason().contains("HTTP_403"));
        assertEquals(3, harness.ranked.size(), "UNKNOWN 不得被当成 CLOSED 丢弃");
    }

    @Test
    void missingCityAndCandidateTypeAreKeptWithWarnings() {
        Harness harness = new Harness(10);
        Map<String, Object> missing = harness.job("城市与类型缺失", null, "BACHELOR", 0.6, "2026-11-06T00:00:00Z");
        missing.remove("cityCode");
        missing.remove("candidateType");
        harness.run(List.of(missing));

        assertEquals(1, harness.ranked.size());
        assertEquals("EXPERIENCED", harness.snapshot.get("candidateType"));
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) harness.snapshot.get("warnings");
        assertTrue(warnings.contains("CITY_UNKNOWN"));
        assertTrue(warnings.contains("CANDIDATE_TYPE_UNKNOWN"));
    }

    private static final class Harness {
        private final JsonService json = new JsonService(new ObjectMapper());
        private final JobSearchMapper mapper = mock(JobSearchMapper.class);
        private final JobMapper jobs = mock(JobMapper.class);
        private final AbilityService ability = mock(AbilityService.class);
        private final ProfileService profiles = mock(ProfileService.class);
        private final int limit;
        private final List<Ranked> ranked = new ArrayList<>();
        private Map<String, Object> snapshot = Map.of();

        private Harness(int limit) {
            this.limit = limit;
            when(jobs.lockBatch("search", "user")).thenReturn(Map.of("status", "GENERATING"));
            when(mapper.sourceId("web")).thenReturn("source");
            when(ability.snapshots("user")).thenReturn(List.of());
            when(profiles.agentSkillCatalog()).thenReturn(List.of());
            JobService jobService = mock(JobService.class);
            when(jobService.roleCode(anyString())).thenReturn("JAVA_BACKEND");
            doAnswer(call -> {
                snapshot = json.map((String) call.getArgument(11));
                ranked.add(new Ranked(
                        (Integer) call.getArgument(4),
                        (String) call.getArgument(5),
                        (String) snapshot.get("title"),
                        (Integer) call.getArgument(7),
                        (String) snapshot.get("recruitmentStatus"),
                        (String) snapshot.get("verificationStatus"),
                        (String) snapshot.get("verificationReason")));
                return 1;
            }).when(jobs).insertItem(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyString(),
                    anyString(), any(), anyString(), anyString(), anyString(), anyString());
            handler = new JobSearchResultHandler(mapper, jobs, jobService, json,
                    new EligibilityService(), new MatchScoreService(), ability, profiles);
        }

        private final JobSearchResultHandler handler;

        private void run(List<Map<String, Object>> items) {
            AiTask task = new AiTask();
            task.setId("task");
            task.setResourceId("search");
            task.setUserId("user");
            task.setInputSnapshotJson(json.stringify(Map.of(
                    "profile", Map.of("targetRole", "JAVA_BACKEND", "candidateType", "EXPERIENCED",
                            "facts", Map.of("education", "BACHELOR")),
                    "filters", Map.of("cities", List.of("110100"), "maxResults", limit))));
            handler.success(task, json.tree(Map.of("items", items)));
        }

        private Map<String, Object> job(String title, String city, String education, double score, String publishedAt) {
            String quote = "要求本科及以上学历";
            Map<String, Object> result = new HashMap<>(Map.of(
                    "sourceKey", "web", "sourceUrl", "https://careers.example.com/jobs/" + title.hashCode(),
                    "title", title, "companyName", "示例公司", "candidateType", "EXPERIENCED",
                    "jdText", education == null ? "负责 Java 后端开发" : quote,
                    "requirements", education == null ? Map.of()
                            : Map.of("educationMin", education, "quotes", Map.of("educationMin", quote)),
                    "semanticScore", score, "publishedAt", publishedAt));
            if (city != null) {
                result.put("cityCode", city);
            }
            result.put("recruitmentStatus", "OPEN");
            result.put("recruitmentEvidence", "DETAIL_APPLY_LINK");
            result.put("recruitmentCheckedAt", Instant.now().toString());
            result.put("verificationStatus", "ACTIVE_CONFIRMED");
            result.put("verificationReason", "ACTIVE_EVIDENCE_FOUND");
            result.put("verificationEvidence", "DETAIL_APPLY_LINK");
            result.put("verificationCheckedAt", Instant.now().toString());
            return result;
        }
    }
}