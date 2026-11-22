package com.aijob.backend.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.aijob.backend.ability.AbilityService;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.task.AiTask;

import tools.jackson.databind.ObjectMapper;

class JobSearchResultHandlerTest {

    @Test
    void taskSummaryRecognizesPipelineWarningsAndSourceFailures() {
        Harness harness = new Harness(10);
        AiTask task = new AiTask();
        task.setResourceId("search");
        task.setUserId("user");
        for (String warning : List.of("SOME_SOURCES_PARTIAL_FAILURE", "SOME_SOURCES_UNAVAILABLE", "SOURCE_PARTIAL")) {
            when(harness.mapper.search("search", "user")).thenReturn(Map.of("summary_json",
                    harness.json.stringify(Map.of("uniqueCount", 1, "warnings", List.of(warning)))));
            assertEquals("PARTIAL", harness.handler.resultSummary(task).get("outcome"), warning);
        }
        when(harness.mapper.search("search", "user")).thenReturn(Map.of("summary_json",
                harness.json.stringify(Map.of("uniqueCount", 0, "warnings", List.of("SOME_SOURCES_PARTIAL_FAILURE")))));
        assertEquals("NO_RESULTS_SOURCE_FAILED", harness.handler.resultSummary(task).get("outcome"));
        when(harness.mapper.search("search", "user")).thenReturn(Map.of("summary_json",
                harness.json.stringify(Map.of("uniqueCount", 0, "warnings", List.of()))));
        assertEquals("EMPTY", harness.handler.resultSummary(task).get("outcome"));
    }

    private record Ranked(int rank, String eligibility, String title, Integer score, String legacyStatus,
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

    @Test
    void preservesOfficialIdentityAndFieldEvidenceInSnapshot() {
        Harness harness = new Harness(10);
        Map<String, Object> job = harness.job("官网后端岗位", "110100", "BACHELOR", 0.5, "2026-11-06T00:00:00Z");
        job.put("sourceKey", "company_official");
        job.put("companySizeText", "2000-5000人");
        job.put("fieldSources", Map.of("salary", Map.of("sourceUrl", "https://www.liepin.com/job/123.shtml",
                "sourceName", "猎聘", "sourceType", "PUBLIC_SEARCH", "rawText", "100-150元/天")));
        job.put("updatedAt", "2026-07-21T00:00:00Z");
        harness.run(List.of(job));
        assertEquals("2000-5000人", harness.snapshot.get("companySizeText"));
        assertEquals("2026-07-21T00:00:00Z", harness.snapshot.get("updatedAt"));
        assertEquals("OFFICIAL", ((Map<?, ?>) harness.snapshot.get("source")).get("type"));
        assertTrue(((Map<?, ?>) harness.snapshot.get("fieldSources")).containsKey("salary"));
    }

    @Test
    void skillOnlyRequirementLeavesHardEligibilityUnknown() {
        Harness harness = new Harness(10);
        Map<String, Object> job = harness.job("Java 后端", "110100", null, 0.5, "2026-11-06T00:00:00Z");
        job.put("requirements", Map.of("skills", List.of("JAVA")));
        when(harness.profiles.agentSkillCatalog()).thenReturn(List.of(Map.of("code", "JAVA")));
        harness.run(List.of(job));
        assertEquals("UNKNOWN", harness.ranked.getFirst().eligibility());
    }

    @Test
    void checksExplicitGraduationCohortEvenWhenExtractionOmitsIt() {
        Harness harness = new Harness(10);
        Map<String, Object> job = harness.job("Java 后端", "110100", "BACHELOR", 0.5, "2026-11-06T00:00:00Z");
        job.put("jdText", "要求本科及以上学历，面向 2028 届或 2029 届毕业生");
        harness.run(List.of(job));
        assertEquals("UNKNOWN", harness.ranked.getFirst().eligibility());
    }

    @Test
    void reconcilesEducationCodeWithExplicitQuotedDegree() {
        Harness harness = new Harness(10);
        harness.run(List.of(harness.job("Java 后端", "110100", "MASTER", 0.5, "2026-11-06T00:00:00Z")));
        assertEquals("PASS", harness.ranked.getFirst().eligibility());
    }

    @Test
    void replaysCapturedAgentResponsesThroughJavaRanking() throws Exception {
        String directory = System.getProperty("jobSearchReplayDir");
        assumeTrue(directory != null && !directory.isBlank());
        ObjectMapper objectMapper = new ObjectMapper();
        try (var files = Files.list(Path.of(directory))) {
            var cases = files.filter(file -> file.getFileName().toString().endsWith(".json")).sorted().toList();
            assertTrue(!cases.isEmpty());
            for (Path file : cases) {
                var captured = objectMapper.readTree(Files.readString(file));
                var harness = new Harness(30);
                var payload = captured.get("payload");
                var result = captured.get("result");
                doCallRealMethod().when(harness.jobService).roleCode(anyString());
                when(harness.mapper.sourceId(anyString())).thenAnswer(call -> "source-" + call.getArgument(0));
                when(harness.profiles.agentSkillCatalog()).thenReturn(harness.json.map(payload).get("skillCatalog")
                        instanceof List<?> skills ? (List) skills : List.of());
                harness.run(payload.toString(), result);
                assertEquals(result.get("items").size(), harness.summary.get("rawCount"));
                assertEquals(harness.ranked.size(), harness.summary.get("uniqueCount"));
                var rankedEligibility = harness.ranked.stream().collect(Collectors.groupingBy(
                        Ranked::eligibility, Collectors.counting()));
                Map<String, Object> report = new HashMap<>(harness.summary);
                report.remove("agentMetrics");
                report.remove("agentDedupe");
                report.put("rankedEligibility", rankedEligibility);
                report.put("rankedStale", harness.snapshots.stream().filter(item ->
                        ((List<?>) item.get("warnings")).contains("STALE_POSTING")).count());
                System.out.println("JOB_SEARCH_REPLAY " + file.getFileName() + " " + harness.json.stringify(report));
            }
        }
    }

    private static final class Harness {
        private final JsonService json = new JsonService(new ObjectMapper());
        private final JobSearchMapper mapper = mock(JobSearchMapper.class);
        private final JobMapper jobs = mock(JobMapper.class);
        private final AbilityService ability = mock(AbilityService.class);
        private final ProfileService profiles = mock(ProfileService.class);
        private final JobService jobService = mock(JobService.class);
        private final int limit;
        private final List<Ranked> ranked = new ArrayList<>();
        private Map<String, Object> snapshot = Map.of();
        private Map<String, Object> summary = Map.of();
        private final List<Map<String, Object>> snapshots = new ArrayList<>();

        private Harness(int limit) {
            this.limit = limit;
            when(jobs.lockBatch("search", "user")).thenReturn(Map.of("status", "GENERATING"));
            when(mapper.sourceId("web")).thenReturn("source");
            when(mapper.sourceId("company_official")).thenReturn("official-source");
            when(ability.snapshots("user")).thenReturn(List.of());
            when(profiles.agentSkillCatalog()).thenReturn(List.of());
            when(jobService.roleCode(anyString())).thenReturn("JAVA_BACKEND");
            doAnswer(call -> {
                snapshot = json.map((String) call.getArgument(11));
                snapshots.add(snapshot);
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
            doAnswer(call -> {
                summary = json.map((String) call.getArgument(4));
                return 1;
            }).when(mapper).complete(anyString(), anyString(), anyString(), anyString(), anyString());
            handler = new JobSearchResultHandler(mapper, jobs, jobService, json,
                    new EligibilityService(), new MatchScoreService(), ability, profiles);
        }

        private final JobSearchResultHandler handler;

        private void run(List<Map<String, Object>> items) {
            run(json.stringify(Map.of(
                    "profile", Map.of("targetRole", "JAVA_BACKEND", "candidateType", "EXPERIENCED",
                            "facts", Map.of("education", "BACHELOR")),
                    "filters", Map.of("cities", List.of("110100"), "maxResults", limit))),
                    json.tree(Map.of("items", items)));
        }

        private void run(String payload, tools.jackson.databind.JsonNode result) {
            AiTask task = new AiTask();
            task.setId("task");
            task.setResourceId("search");
            task.setUserId("user");
            task.setInputSnapshotJson(payload);
            handler.success(task, result);
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
