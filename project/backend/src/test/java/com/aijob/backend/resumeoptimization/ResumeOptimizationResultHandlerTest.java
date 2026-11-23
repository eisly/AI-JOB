package com.aijob.backend.resumeoptimization;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.task.AiTask;

import tools.jackson.databind.json.JsonMapper;

class ResumeOptimizationResultHandlerTest {

    private ResumeOptimizationMapper mapper;
    private JsonService json;
    private ResumeOptimizationResultHandler handler;

    @BeforeEach
    void setUp() {
        mapper = mock(ResumeOptimizationMapper.class);
        json = new JsonService(JsonMapper.builder().build());
        handler = new ResumeOptimizationResultHandler(mapper, json);
    }

    @Test
    void diagnosisAssignsJavaIdsAndPersistsValidatedQuoteAndAssessment() {
        AiTask task = task("RESUME_DIAGNOSE", "诊断原文：使用 Redis 缓存。");
        when(mapper.lockDiagnosis("resource", "user")).thenReturn(Map.of("id", "resource"));
        when(mapper.completeDiagnosis(eq("resource"), eq("task"), anyString(), anyString(), anyString(), anyString(), anyBoolean())).thenReturn(1);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("priority", "IMPORTANT");
        item.put("title", "补充量化结果");
        item.put("issue", "缺少结果");
        item.put("quote", "使用 Redis 缓存");
        item.put("rationale", "无法判断成效");
        item.put("suggestion", "补充真实指标");

        int version = handler.success(task, json.tree(Map.of("items", List.of(item),
                "assessment", assessment(18, 12, 14, 0, 9))));

        ArgumentCaptor<String> savedItems = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> savedAssessment = ArgumentCaptor.forClass(String.class);
verify(mapper).completeDiagnosis(eq("resource"), eq("task"), savedItems.capture(),
                savedAssessment.capture(), anyString(), anyString(), anyBoolean());
        List<Map<String, Object>> items = json.convert(json.parse(savedItems.getValue()), List.class);
        assertNotNull(items.get(0).get("id"));
        assertFalse(items.get(0).containsKey("diagnosisId"));
        assertEquals(1, version);
        Map<String, Object> assessment = json.convert(json.parse(savedAssessment.getValue()), Map.class);
        assertEquals("resume-quality-v1", assessment.get("ruleVersion"));
        assertEquals(53, ((Number) assessment.get("totalScore")).intValue());
        assertNotNull(assessment.get("assessedAt"));
        List<Map<String, Object>> dimensions = (List<Map<String, Object>>) assessment.get("dimensions");
        assertEquals(5, dimensions.size());
        assertEquals("使用 Redis 缓存", dimensions.get(0).get("quote"));
        assertNull(dimensions.get(3).get("quote"));
        assertEquals(1, ((List<?>) assessment.get("strengths")).size());
    }

    @Test
    void diagnosisRejectsMissingAssessment() {
        AiTask task = task("RESUME_DIAGNOSE", "诊断原文：使用 Redis 缓存。");
        when(mapper.lockDiagnosis("resource", "user")).thenReturn(Map.of("id", "resource"));

        assertThrows(BusinessException.class, () -> handler.success(task,
                json.tree(Map.of("items", List.of(item())))));
        verify(mapper, never()).completeDiagnosis(any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void diagnosisRejectsDuplicateDimension() {
        AiTask task = task("RESUME_DIAGNOSE", "诊断原文：使用 Redis 缓存。");
        when(mapper.lockDiagnosis("resource", "user")).thenReturn(Map.of("id", "resource"));
        Map<String, Object> assessment = assessment(18, 12, 14, 10, 9);
        List<Map<String, Object>> dimensions = (List<Map<String, Object>>) assessment.get("dimensions");
        dimensions.get(1).put("code", "EXPERIENCE_PROJECTS");

        assertThrows(BusinessException.class, () -> handler.success(task,
                json.tree(Map.of("items", List.of(item()), "assessment", assessment))));
        verify(mapper, never()).completeDiagnosis(any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void diagnosisRejectsMissingDimension() {
        AiTask task = task("RESUME_DIAGNOSE", "诊断原文：使用 Redis 缓存。");
        when(mapper.lockDiagnosis("resource", "user")).thenReturn(Map.of("id", "resource"));
        Map<String, Object> assessment = assessment(18, 12, 14, 10, 9);
        ((List<?>) assessment.get("dimensions")).remove(4);

        assertThrows(BusinessException.class, () -> handler.success(task,
                json.tree(Map.of("items", List.of(item()), "assessment", assessment))));
        verify(mapper, never()).completeDiagnosis(any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void diagnosisRejectsScoreAboveMax() {
        AiTask task = task("RESUME_DIAGNOSE", "诊断原文：使用 Redis 缓存。");
        when(mapper.lockDiagnosis("resource", "user")).thenReturn(Map.of("id", "resource"));
        Map<String, Object> assessment = assessment(31, 12, 14, 10, 9);

        assertThrows(BusinessException.class, () -> handler.success(task,
                json.tree(Map.of("items", List.of(item()), "assessment", assessment))));
        verify(mapper, never()).completeDiagnosis(any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void diagnosisRejectsNonIntegerScore() {
        AiTask task = task("RESUME_DIAGNOSE", "诊断原文：使用 Redis 缓存。");
        when(mapper.lockDiagnosis("resource", "user")).thenReturn(Map.of("id", "resource"));
        Map<String, Object> assessment = assessment(18, 12.5, 14, 10, 9);

        assertThrows(BusinessException.class, () -> handler.success(task,
                json.tree(Map.of("items", List.of(item()), "assessment", assessment))));
        verify(mapper, never()).completeDiagnosis(any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void diagnosisRejectsPositiveScoreWithoutQuote() {
        AiTask task = task("RESUME_DIAGNOSE", "诊断原文：使用 Redis 缓存。");
        when(mapper.lockDiagnosis("resource", "user")).thenReturn(Map.of("id", "resource"));
        Map<String, Object> assessment = assessment(18, 12, 14, 10, 9);
        ((List<Map<String, Object>>) assessment.get("dimensions")).get(2).put("quote", null);

        assertThrows(BusinessException.class, () -> handler.success(task,
                json.tree(Map.of("items", List.of(item()), "assessment", assessment))));
        verify(mapper, never()).completeDiagnosis(any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void diagnosisRejectsAssessmentQuoteOutsideAuthorizedDocument() {
        AiTask task = task("RESUME_DIAGNOSE", "诊断原文：使用 Redis 缓存。");
        when(mapper.lockDiagnosis("resource", "user")).thenReturn(Map.of("id", "resource"));
        Map<String, Object> assessment = assessment(18, 12, 14, 10, 9);
        ((List<Map<String, Object>>) assessment.get("dimensions")).get(0).put("quote", "简历中不存在的句子");

        assertThrows(BusinessException.class, () -> handler.success(task,
                json.tree(Map.of("items", List.of(item()), "assessment", assessment))));
        verify(mapper, never()).completeDiagnosis(any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void diagnosisRejectsStrengthQuoteOutsideAuthorizedDocument() {
        AiTask task = task("RESUME_DIAGNOSE", "诊断原文：使用 Redis 缓存。");
        when(mapper.lockDiagnosis("resource", "user")).thenReturn(Map.of("id", "resource"));
        Map<String, Object> assessment = assessment(18, 12, 14, 10, 9);
        ((List<Map<String, Object>>) assessment.get("strengths")).get(0).put("quote", "简历中不存在的优势");

        assertThrows(BusinessException.class, () -> handler.success(task,
                json.tree(Map.of("items", List.of(item()), "assessment", assessment))));
        verify(mapper, never()).completeDiagnosis(any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void diagnosisRejectsWrongRuleVersion() {
        AiTask task = task("RESUME_DIAGNOSE", "诊断原文：使用 Redis 缓存。");
        when(mapper.lockDiagnosis("resource", "user")).thenReturn(Map.of("id", "resource"));
        Map<String, Object> assessment = assessment(18, 12, 14, 10, 9);
        assessment.put("ruleVersion", "resume-quality-v3");

        assertThrows(BusinessException.class, () -> handler.success(task,
                json.tree(Map.of("items", List.of(item()), "assessment", assessment))));
        verify(mapper, never()).completeDiagnosis(any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void optimizationRejectsOriginalTextOutsideAuthorizedDocument() {
        AiTask task = task("RESUME_OPTIMIZE", "仅包含真实经历。");
        when(mapper.lockOptimization("resource", "user")).thenReturn(Map.of("id", "resource"));
        Map<String, Object> section = Map.of("sectionKey", "work", "title", "工作经历",
                "originalText", "虚构工作经历", "suggestedText", "优化建议", "sourceQuotes", List.of());

        assertThrows(BusinessException.class, () -> handler.success(task,
                json.tree(Map.of("title", "简历", "sections", List.of(section)))));
        verify(mapper, never()).completeOptimization(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void optimizationRejectsSourceQuoteOutsideAuthorizedDocument() {
        AiTask task = task("RESUME_OPTIMIZE", "真实项目原文。");
        when(mapper.lockOptimization("resource", "user")).thenReturn(Map.of("id", "resource"));
        Map<String, Object> section = Map.of("sectionKey", "project", "title", "项目",
                "originalText", "真实项目原文", "suggestedText", "优化建议", "sourceQuotes", List.of("其他原文"));

        assertThrows(BusinessException.class, () -> handler.success(task,
                json.tree(Map.of("title", "简历", "sections", List.of(section)))));
        verify(mapper, never()).completeOptimization(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void optimizationFullDocumentAllowsNoSuggestionsAndKeepsMissingRequirements() {
        String text = "项目经历：使用 Redis 缓存商品详情。";
        AiTask task = fullDocumentTask(text, "熟悉 Redis 缓存一致性方案。\n具备 Spring Boot 开发经验。");
        when(mapper.lockOptimization("resource", "user")).thenReturn(Map.of("id", "resource"));
        when(mapper.completeOptimization(eq("resource"), eq("task"), eq("定向简历"), eq("[]"), anyString(),
                anyString(), any(), any())).thenReturn(1);
        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("requirement", "不存在的岗位要求");

        assertEquals(1, handler.success(task, json.tree(Map.of(
                "title", "定向简历",
                "sections", List.of(),
                "missingRequirements", List.of(
                        Map.of("requirement", "熟悉 Redis 缓存一致性方案。",
                                "note", "简历只提到用 Redis 缓存商品详情。"),
                        invalid)))));

        ArgumentCaptor<String> capturedGaps = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> capturedNotes = ArgumentCaptor.forClass(String.class);
        verify(mapper).completeOptimization(eq("resource"), eq("task"), eq("定向简历"), eq("[]"),
                capturedGaps.capture(), capturedNotes.capture(), any(), any());
        List<Map<String, Object>> savedGaps = json.convert(json.parse(capturedGaps.getValue()), List.class);
        assertEquals(1, savedGaps.size());
        assertEquals("熟悉 Redis 缓存一致性方案。", savedGaps.get(0).get("requirement"));
        assertTrue(json.parse(capturedNotes.getValue()).toString().contains("无法在 JD 中定位"));
    }

    @Test
    void optimizationFullDocumentSkipsDuplicateLocationsAndUnsupportedNumbers() {
        String text = "实习经历：使用 Redis 缓存商品详情。项目经历：使用 Redis 缓存商品详情。技能：了解 MySQL 索引优化。";
        AiTask task = fullDocumentTask(text, "熟悉 Redis 缓存一致性方案。");
        when(mapper.lockOptimization("resource", "user")).thenReturn(Map.of("id", "resource"));
        when(mapper.completeOptimization(eq("resource"), eq("task"), eq("定向简历"), eq("[]"), anyString(),
                anyString(), any(), any())).thenReturn(1);
        Map<String, Object> duplicate = new LinkedHashMap<>();
        duplicate.put("sectionKey", "internship");
        duplicate.put("title", "实习经历");
        duplicate.put("originalText", "使用 Redis 缓存商品详情。");
        duplicate.put("suggestedText", "通过 Redis 缓存商品详情。");
        duplicate.put("sourceQuotes", List.of());
        Map<String, Object> invented = new LinkedHashMap<>();
        invented.put("sectionKey", "skills");
        invented.put("title", "技能");
        invented.put("originalText", "了解 MySQL 索引优化。");
        invented.put("suggestedText", "了解 MySQL 索引优化，查询性能提升40%。");
        invented.put("sourceQuotes", List.of());

        assertEquals(1, handler.success(task, json.tree(Map.of(
                "title", "定向简历", "sections", List.of(duplicate, invented)))));

        ArgumentCaptor<String> capturedNotes = ArgumentCaptor.forClass(String.class);
        verify(mapper).completeOptimization(eq("resource"), eq("task"), eq("定向简历"), eq("[]"),
                anyString(), capturedNotes.capture(), any(), any());
        String notes = json.parse(capturedNotes.getValue()).toString();
        assertTrue(notes.contains("无法唯一定位"));
        assertTrue(notes.contains("不存在的数字"));
    }

    @Test
    void optimizationSkipsUnchangedSuggestionsWithoutBlockingRealRewrites() {
        String original = "开发缓存模块\n并联调查询接口。";
        AiTask task = fullDocumentTask("项目经历：" + original, "具备接口联调经验。");
        when(mapper.lockOptimization("resource", "user")).thenReturn(Map.of("id", "resource"));
        when(mapper.completeOptimization(eq("resource"), eq("task"), anyString(), anyString(), anyString(),
                anyString(), any(), any())).thenReturn(1);
        Map<String, Object> unchanged = Map.of("sectionKey", "same", "title", "缓存模块",
                "originalText", original, "suggestedText", "  " + original.replace("\n", "\r\n") + "  ",
                "sourceQuotes", List.of());
        Map<String, Object> rewrite = Map.of("sectionKey", "rewrite", "title", "接口联调",
                "originalText", original, "suggestedText", "完成查询接口联调与缓存模块开发。",
                "sourceQuotes", List.of());

        assertEquals(1, handler.success(task, json.tree(Map.of(
                "title", "定向简历", "sections", List.of(unchanged, rewrite)))));

        ArgumentCaptor<String> capturedSections = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> capturedNotes = ArgumentCaptor.forClass(String.class);
        verify(mapper).completeOptimization(eq("resource"), eq("task"), anyString(), capturedSections.capture(),
                anyString(), capturedNotes.capture(), any(), any());
        List<Map<String, Object>> saved = json.convert(json.parse(capturedSections.getValue()), List.class);
        assertEquals(1, saved.size());
        assertEquals("rewrite", saved.get(0).get("sectionKey"));
        assertTrue(capturedNotes.getValue().contains("建议与简历原文一致"));
    }

    @Test
    void optimizationClearsJdQuoteOutsideJdTextAndKeepsSection() {
        String text = "项目经历：使用 Redis 缓存商品详情。";
        AiTask task = fullDocumentTask(text, "熟悉 Spring Boot 开发。");
        when(mapper.lockOptimization("resource", "user")).thenReturn(Map.of("id", "resource"));
        when(mapper.completeOptimization(eq("resource"), eq("task"), anyString(), anyString(), anyString(),
                anyString(), any(), any())).thenReturn(1);
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("sectionKey", "project");
        section.put("title", "项目经历");
        section.put("originalText", "使用 Redis 缓存商品详情。");
        section.put("suggestedText", "通过 Redis 缓存商品详情。");
        section.put("sourceQuotes", List.of());
        section.put("jdQuote", "熟悉 Kubernetes 集群运维");
        section.put("reason", "对应岗位要求。");

        assertEquals(1, handler.success(task, json.tree(Map.of(
                "title", "定向简历", "sections", List.of(section)))));

        ArgumentCaptor<String> capturedSections = ArgumentCaptor.forClass(String.class);
        verify(mapper).completeOptimization(eq("resource"), eq("task"), anyString(), capturedSections.capture(),
                anyString(), anyString(), any(), any());
        List<Map<String, Object>> saved = json.convert(json.parse(capturedSections.getValue()), List.class);
        assertEquals(1, saved.size());
        assertNull(saved.get(0).get("jdQuote"));
        assertEquals("对应岗位要求。", saved.get(0).get("reason"));
    }

    @Test
    void diagnosisAcceptsV2WithAdditionalQuotesAndPersistsSnapshotMetadata() {
        String text = "诊断原文：使用 Redis 缓存。\n项目经历：独立完成缓存改造。";
        AiTask task = task("RESUME_DIAGNOSE", text);
        when(mapper.lockDiagnosis("resource", "user")).thenReturn(Map.of("id", "resource"));
        when(mapper.completeDiagnosis(eq("resource"), eq("task"), anyString(), anyString(), anyString(),
                anyString(), anyBoolean())).thenReturn(1);
        Map<String, Object> item = item();
        item.put("quotes", List.of("项目经历：独立完成缓存改造。"));
        item.put("impact", "招聘方无法判断个人贡献");
        item.put("example", "在项目经历中补充：独立完成缓存改造。");
        item.put("pendingVerification", List.of("实际缓存命中率"));
        Map<String, Object> assessment = assessment(18, 12, 14, 10, 9);
        assessment.put("ruleVersion", "resume-quality-v2");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dimensions = (List<Map<String, Object>>) assessment.get("dimensions");
        dimensions.get(0).put("quotes", List.of("项目经历：独立完成缓存改造。"));
        @SuppressWarnings("unchecked")
        Map<String, Object> strength = (Map<String, Object>) ((List<?>) assessment.get("strengths")).get(0);
        strength.put("quotes", List.of("项目经历：独立完成缓存改造。"));
        strength.put("approach", "独立完成缓存改造");
        strength.put("contribution", "本人完成实现");
        strength.put("result", "原文未给出可核实结果");

        handler.success(task, json.tree(Map.of("items", List.of(item), "assessment", assessment)));

        ArgumentCaptor<String> savedItems = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> savedAssessment = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> savedText = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> savedLayout = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> savedVerified = ArgumentCaptor.forClass(Boolean.class);
        verify(mapper).completeDiagnosis(eq("resource"), eq("task"), savedItems.capture(),
                savedAssessment.capture(), savedText.capture(), savedLayout.capture(), savedVerified.capture());
        assertEquals(text, savedText.getValue());
        assertEquals("pdf-layout-v2", savedLayout.getValue());
        assertTrue(savedVerified.getValue());

        List<Map<String, Object>> items = json.convert(json.parse(savedItems.getValue()), List.class);
        assertEquals(List.of("项目经历：独立完成缓存改造。"), items.get(0).get("quotes"));
        assertEquals("招聘方无法判断个人贡献", items.get(0).get("impact"));
        assertEquals(List.of("实际缓存命中率"), items.get(0).get("pendingVerification"));
        Map<String, Object> saved = json.convert(json.parse(savedAssessment.getValue()), Map.class);
        assertEquals("resume-quality-v2", saved.get("ruleVersion"));
        assertEquals(63, ((Number) saved.get("totalScore")).intValue());
        List<Map<String, Object>> savedDimensions = (List<Map<String, Object>>) saved.get("dimensions");
        assertEquals(List.of("项目经历：独立完成缓存改造。"), savedDimensions.get(0).get("quotes"));
        Map<String, Object> savedStrength = (Map<String, Object>) ((List<?>) saved.get("strengths")).get(0);
        assertEquals("独立完成缓存改造", savedStrength.get("approach"));
        assertEquals("本人完成实现", savedStrength.get("contribution"));
        assertEquals("原文未给出可核实结果", savedStrength.get("result"));
    }

    @Test
    void diagnosisRejectsAdditionalQuoteOutsideAuthorizedDocument() {
        AiTask task = task("RESUME_DIAGNOSE", "诊断原文：使用 Redis 缓存。");
        when(mapper.lockDiagnosis("resource", "user")).thenReturn(Map.of("id", "resource"));
        Map<String, Object> item = item();
        item.put("quotes", List.of("原文不存在的补充引用"));

        assertThrows(BusinessException.class, () -> handler.success(task,
                json.tree(Map.of("items", List.of(item), "assessment", assessment(18, 12, 14, 10, 9)))));
        verify(mapper, never()).completeDiagnosis(any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void diagnosisV3RecomputesScoresFromLevelsAndAcceptsNoSuggestions() {
        String source = "项目经历：使用 Redis 缓存。";
        AiTask task = task("RESUME_DIAGNOSE", source);
        task.setInputSnapshotJson(json.stringify(Map.of(
                "document", Map.of("text", source),
                "assessmentRules", ResumeAssessmentRules.currentPayload())));
        when(mapper.lockDiagnosis("resource", "user")).thenReturn(Map.of("id", "resource"));
        when(mapper.completeDiagnosis(eq("resource"), eq("task"), anyString(), anyString(),
                anyString(), anyString(), anyBoolean())).thenReturn(1);
        Map<String, Object> assessment = assessment(99, 99, 99, 99, 99);
        assessment.put("ruleVersion", ResumeAssessmentRules.CURRENT_VERSION);
        assessment.put("summary", "已说明缓存改造，个人职责仍可细化。");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dimensions = (List<Map<String, Object>>) assessment.get("dimensions");
        for (Map<String, Object> dimension : dimensions) {
            ResumeAssessmentRules.Dimension rule = ResumeAssessmentRules.dimension((String) dimension.get("code"));
            Map<String, Integer> levels = new LinkedHashMap<>();
            rule.checks().forEach(check -> levels.put(check.code(), 3));
            dimension.put("levels", levels);
        }

        assertEquals(1, handler.success(task, json.tree(Map.of("items", List.of(), "assessment", assessment))));

        ArgumentCaptor<String> savedAssessment = ArgumentCaptor.forClass(String.class);
        verify(mapper).completeDiagnosis(eq("resource"), eq("task"), eq("[]"), savedAssessment.capture(),
                eq(source), anyString(), anyBoolean());
        var saved = json.parse(savedAssessment.getValue());
        assertEquals(60, saved.path("totalScore").asInt());
        assertEquals(24, saved.path("dimensions").get(0).path("score").asInt());
        assertEquals(6, saved.path("dimensions").get(0).path("criteria").get(0).path("score").asInt());

        Map<String, Object> advice = item();
        advice.put("dimensionCode", "EXPERIENCE_PROJECTS");
        advice.put("checkCode", "timeline");
        assertThrows(BusinessException.class,
                () -> handler.success(task, json.tree(Map.of("items", List.of(advice), "assessment", assessment))));
        advice.put("checkCode", "outcomes");
        assertEquals(1, handler.success(task, json.tree(Map.of("items", List.of(advice), "assessment", assessment))));
        ArgumentCaptor<String> savedItems = ArgumentCaptor.forClass(String.class);
        verify(mapper, times(2)).completeDiagnosis(eq("resource"), eq("task"), savedItems.capture(),
                anyString(), eq(source), anyString(), anyBoolean());
        assertEquals("outcomes", json.parse(savedItems.getValue()).get(0).path("checkCode").asText());
        dimensions.get(0).put("levels", Map.of("problemContext", 3, "personalRole", 3, "approach", 3, "outcomes", 5));
        assertThrows(BusinessException.class,
                () -> handler.success(task, json.tree(Map.of("items", List.of(advice), "assessment", assessment))));

        dimensions.get(0).put("levels", Map.of("problemContext", 6, "personalRole", 3, "approach", 2, "outcomes", 1));
        assertThrows(BusinessException.class,
                () -> handler.success(task, json.tree(Map.of("items", List.of(), "assessment", assessment))));
    }

    private Map<String, Object> item() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("priority", "IMPORTANT");
        item.put("title", "补充量化结果");
        item.put("issue", "缺少结果");
        item.put("quote", "使用 Redis 缓存");
        item.put("rationale", "无法判断成效");
        item.put("suggestion", "补充真实指标");
        return item;
    }

    private Map<String, Object> assessment(Number... scores) {
        String[] codes = {"EXPERIENCE_PROJECTS", "SKILLS_EVIDENCE", "CONTENT_COMPLETENESS",
                "STRUCTURE_CLARITY", "EXPRESSION_PROFESSIONALISM"};
        List<Map<String, Object>> dimensions = new java.util.ArrayList<>();
        for (int index = 0; index < codes.length; index++) {
            Map<String, Object> dimension = new LinkedHashMap<>();
            dimension.put("code", codes[index]);
            dimension.put("score", scores[index]);
            dimension.put("rationale", "评分理由：原文依据充分程度的说明。");
            dimension.put("quote", ((Number) scores[index]).intValue() > 0 ? "使用 Redis 缓存" : null);
            dimensions.add(dimension);
        }
        Map<String, Object> strength = new LinkedHashMap<>();
        strength.put("title", "经历表述具体");
        strength.put("quote", "使用 Redis 缓存");
        strength.put("description", "该段经历描述了具体技术场景。");
        Map<String, Object> assessment = new LinkedHashMap<>();
        assessment.put("ruleVersion", "resume-quality-v1");
        assessment.put("dimensions", dimensions);
        assessment.put("strengths", List.of(strength));
        return assessment;
    }

    private AiTask task(String operation, String text) {
        AiTask task = new AiTask();
        task.setId("task");
        task.setUserId("user");
        task.setResourceId("resource");
        task.setOperation(operation);
        task.setInputSnapshotJson(json.stringify(Map.of("document", Map.of("text", text,
                "textLayoutVersion", "pdf-layout-v2", "readOrderVerified", true))));
        return task;
    }

    private AiTask fullDocumentTask(String text, String jdText) {
        AiTask task = task("RESUME_OPTIMIZE", text);
        task.setInputSnapshotJson(json.stringify(Map.of(
                "document", Map.of("text", text, "textLayoutVersion", "pdf-layout-v2", "readOrderVerified", true),
                "jdText", jdText,
                "mode", "FULL_DOCUMENT")));
        return task;
    }
}
