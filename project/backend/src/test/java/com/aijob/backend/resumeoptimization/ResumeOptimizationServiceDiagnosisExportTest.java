package com.aijob.backend.resumeoptimization;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.job.JobMapper;
import com.aijob.backend.resume.FileStorageService;
import com.aijob.backend.resume.ResumeMapper;
import com.aijob.backend.task.IdempotencyService;
import com.aijob.backend.task.TaskService;

import tools.jackson.databind.json.JsonMapper;

class ResumeOptimizationServiceDiagnosisExportTest {

    private ResumeOptimizationMapper mapper;
    private ResumeMapper resumes;
    private JobMapper jobs;
    private TaskService tasks;
    private IdempotencyService idempotency;
    private FileStorageService files;
    private ResumeOptimizationService service;
    private JsonService json;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(ResumeOptimizationMapper.class);
        resumes = mock(ResumeMapper.class);
        jobs = mock(JobMapper.class);
        tasks = mock(TaskService.class);
        files = mock(FileStorageService.class);
        idempotency = mock(IdempotencyService.class);
        json = new JsonService(JsonMapper.builder().build());
        when(idempotency.execute(anyString(), anyString(), anyString(), anyString(), any(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    ((java.util.function.Supplier<Object>) invocation.getArgument(6)).get();
                    return null;
                });
        service = new ResumeOptimizationService(mapper, resumes, jobs, tasks, idempotency,
                new ResumeExportService("C:\\Windows\\Fonts\\simhei.ttf"), files, json, new ApiView(json));
    }

    @Test
    void exportDiagnosisRejectsUnknownOrForeignRecordWith404() {
        when(mapper.diagnosis("missing", "user")).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.exportDiagnosis("user", "missing"));
    }

    @Test
    void exportDiagnosisRejectsNotReadyRecordWith409() {
        when(mapper.diagnosis("id", "user")).thenReturn(row("GENERATING", null));
        assertThrows(BusinessException.class, () -> service.exportDiagnosis("user", "id"));
    }

    @Test
    void exportDiagnosisRejectsReadyRecordWithoutAssessmentWith409() {
        when(mapper.diagnosis("id", "user")).thenReturn(row("READY", null));
        assertThrows(BusinessException.class, () -> service.exportDiagnosis("user", "id"));
    }

    @Test
    void exportDiagnosisGeneratesPdfFromSavedAssessment() throws Exception {
        when(mapper.diagnosis("id", "user")).thenReturn(row("READY", assessmentJson()));

        ResumeOptimizationService.ExportContent content = service.exportDiagnosis("user", "id");

        assertEquals("resume-assessment-id.pdf", content.fileName());
        assertEquals("application/pdf", content.mediaType());
        try (PDDocument document = Loader.loadPDF(content.bytes())) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("简历评分报告"));
            assertTrue(text.contains("总分：63 / 100"));
            assertTrue(text.contains("经历与项目"));
            assertTrue(text.contains("18/30"));
            assertTrue(text.contains("优先改进建议"));
            assertTrue(text.contains("优势"));
        }
    }

    @Test
    void diagnosisVoAndPdfUseTheSavedRuleVersion() throws Exception {
        for (String version : List.of("resume-quality-v2", ResumeAssessmentRules.CURRENT_VERSION)) {
            boolean current = ResumeAssessmentRules.CURRENT_VERSION.equals(version);
            var assessment = json.map(json.parse(assessmentJson()));
            var row = row("READY", null);
            assessment.put("ruleVersion", version);
            if (current) {
                assessment.put("summary", "建议说明缓存改造的成果。");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> dimensions = (List<Map<String, Object>>) assessment.get("dimensions");
                dimensions.get(0).put("criteria", List.of(Map.of(
                        "code", "personalRole", "label", "个人职责", "score", 6, "maxScore", 10)));
                row.put("items_json", itemsJson(true));
            }
            row.put("assessment_json", json.stringify(assessment));
            when(mapper.diagnosis("id", "user")).thenReturn(row);
            var report = json.tree(service.diagnosis("user", "id"));
            var vo = report.path("assessment");
            assertEquals(current, report.path("items").get(0).has("checkCode"));
            int maxScore = current ? 40 : 30;
            assertEquals(maxScore, vo.path("dimensions").get(0).path("maxScore").asInt());
            try (PDDocument document = Loader.loadPDF(service.exportDiagnosis("user", "id").bytes())) {
                String text = new PDFTextStripper().getText(document);
                assertTrue(text.contains("18/" + maxScore));
                if (current) {
                    assertTrue(text.contains("建议说明缓存改造的成果"));
                    assertTrue(text.contains("个人职责：6/10"));
                }
                assertEquals(current, text.contains("个人职责 6/10"));
            }
        }
    }

    @Test
    void exportDiagnosisPaginatesLongChineseReportConsistently() throws Exception {
        when(mapper.diagnosis("id", "user")).thenReturn(row("READY", longAssessmentJson()));

        ResumeOptimizationService.ExportContent content = service.exportDiagnosis("user", "id");

        try (PDDocument document = Loader.loadPDF(content.bytes())) {
            assertTrue(document.getNumberOfPages() > 1, "长报告应分页，实际页数=" + document.getNumberOfPages());
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("简历评分报告"));
            assertTrue(text.contains("总分：63 / 100"));
            assertTrue(text.contains("经历与项目"));
            assertTrue(text.contains("结构清晰度"));
            assertTrue(text.contains("优先改进建议"));
        }
    }

    private String longAssessmentJson() {
        StringBuilder rationale = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            rationale.append("该维度评分理由说明：原文呈现了职责描述但个人贡献与成果不够明确，需补充可核实的结果。");
        }
        List<Map<String, Object>> dimensions = List.of(
                longDimension("EXPERIENCE_PROJECTS", 18, rationale.toString()),
                longDimension("SKILLS_EVIDENCE", 12, rationale.toString()),
                longDimension("CONTENT_COMPLETENESS", 14, rationale.toString()),
                longDimension("STRUCTURE_CLARITY", 10, rationale.toString()),
                longDimension("EXPRESSION_PROFESSIONALISM", 9, rationale.toString()));
        Map<String, Object> strength = new LinkedHashMap<>();
        strength.put("title", "经历表述具体");
        strength.put("quote", "使用 Redis 缓存");
        strength.put("description", "该段经历描述了具体技术场景。");
        Map<String, Object> assessment = new LinkedHashMap<>();
        assessment.put("ruleVersion", "resume-quality-v1");
        assessment.put("totalScore", 63);
        assessment.put("dimensions", dimensions);
        assessment.put("strengths", List.of(strength));
        assessment.put("assessedAt", "2026-11-20T12:00:00Z");
        return json.stringify(assessment);
    }

    private Map<String, Object> longDimension(String code, int score, String rationale) {
        Map<String, Object> dimension = new LinkedHashMap<>();
        dimension.put("code", code);
        dimension.put("score", score);
        dimension.put("rationale", rationale);
        dimension.put("quote", "使用 Redis 缓存");
        return dimension;
    }

    @Test
    void diagnosisVoExposesNullableAssessmentForLegacyRecords() {
        when(mapper.diagnosis("legacy", "user")).thenReturn(row("READY", null));
        Map<String, Object> vo = service.diagnosis("user", "legacy");
        assertTrue(vo.containsKey("assessment"));
        assertEquals(null, vo.get("assessment"));
        assertEquals(false, vo.get("readOrderVerified"));
    }

    @Test
    void diagnoseUsesReExtractedLayoutTextWhenOriginalFileAvailable() {
        String versionId = "20000000-0000-4000-8000-000000000002";
        when(resumes.version(versionId, "user")).thenReturn(version(versionId, "历史旧文本", "legacy-v1"));
        when(files.reExtract("user", "user/" + versionId, "application/pdf"))
                .thenReturn(new FileStorageService.ExtractedText(
                        "[page:1]\n教育背景\n[page:2]\n项目经历", FileStorageService.PDF_LAYOUT_VERSION));

        service.diagnose("user", "/api/v1/resume-optimizations/diagnoses", "key",
                Map.of("resumeVersionId", versionId));

        Map<String, Object> document = capturedDiagnosisDocument();
        assertEquals("[page:1]\n教育背景\n[page:2]\n项目经历", document.get("text"));
        assertEquals(FileStorageService.PDF_LAYOUT_VERSION, document.get("textLayoutVersion"));
        assertEquals(true, document.get("readOrderVerified"));
    }

    @Test
    void diagnoseFallsBackToSavedTextAndMarksReadOrderUnverified() {
        String versionId = "20000000-0000-4000-8000-000000000002";
        when(resumes.version(versionId, "user")).thenReturn(version(versionId, "已保存的简历文本", null));
        when(files.reExtract("user", "user/" + versionId, "application/pdf")).thenReturn(null);

        service.diagnose("user", "/api/v1/resume-optimizations/diagnoses", "key",
                Map.of("resumeVersionId", versionId));

        Map<String, Object> document = capturedDiagnosisDocument();
        assertEquals("已保存的简历文本", document.get("text"));
        assertEquals(FileStorageService.LEGACY_LAYOUT_VERSION, document.get("textLayoutVersion"));
        assertEquals(false, document.get("readOrderVerified"));
    }

    @Test
    void optimizeReusesDiagnosisSourceTextSnapshot() {
        String versionId = "20000000-0000-4000-8000-000000000002";
        String jobId = "30000000-0000-4000-8000-000000000001";
        String diagnosisId = "40000000-0000-4000-8000-000000000002";
        when(resumes.version(versionId, "user")).thenReturn(confirmedVersion(versionId, "简历版本旧文本"));
        when(jobs.job(jobId, "user")).thenReturn(job(jobId));
        when(mapper.diagnosis(diagnosisId, "user")).thenReturn(diagnosis(versionId, "诊断授权文本快照"));

        service.optimize("user", "/api/v1/resume-optimizations", "key",
                Map.of("resumeVersionId", versionId, "jobId", jobId, "diagnosisId", diagnosisId));

        Map<String, Object> document = capturedOptimizeDocument();
        assertEquals("诊断授权文本快照", document.get("text"));
        assertEquals(FileStorageService.PDF_LAYOUT_VERSION, document.get("textLayoutVersion"));
        assertEquals(true, document.get("readOrderVerified"));
    }

    @Test
    void optimizeFullDocumentRequiresOwnedPrivateJobAndMatchingVersion() {
        String versionId = "20000000-0000-4000-8000-000000000002";
        String jobId = "30000000-0000-4000-8000-000000000001";
        when(resumes.version(versionId, "user")).thenReturn(confirmedVersion(versionId, "简历原文"));
        Map<String, Object> publicJob = job(jobId);
        publicJob.put("visibility", "PUBLIC");
        when(jobs.job(jobId, "user")).thenReturn(publicJob);

        assertThrows(BusinessException.class, () -> service.optimize("user", "/api/v1/resume-optimizations",
                "key", Map.of("resumeVersionId", versionId, "jobId", jobId, "mode", "FULL_DOCUMENT")));

        Map<String, Object> privateJob = job(jobId);
        privateJob.put("visibility", "PRIVATE");
        privateJob.put("owner_user_id", "user");
        when(jobs.job(jobId, "user")).thenReturn(privateJob);

        assertThrows(BusinessException.class, () -> service.optimize("user", "/api/v1/resume-optimizations",
                "key", Map.of("resumeVersionId", versionId, "jobId", jobId, "mode", "FULL_DOCUMENT",
                        "jobVersion", 2)));

        service.optimize("user", "/api/v1/resume-optimizations", "key",
                Map.of("resumeVersionId", versionId, "jobId", jobId, "mode", "FULL_DOCUMENT", "jobVersion", 1));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(tasks).create(eq("user"), eq("POST"), anyString(), anyString(), any(), eq("RESUME_OPTIMIZE"),
                eq("RESUME_OPTIMIZATION"), anyString(), any(), payload.capture(), any());
        assertEquals("FULL_DOCUMENT", payload.getValue().get("mode"));
    }

    @Test
    void acceptPersistsFullDocumentUsingOnlyChangedSections() {
        String source = "教育背景：本科。\n项目经历：使用Redis缓存商品详情。\n技能：熟悉Java。";
        Map<String, Object> row = optimizationRow("READY");
        row.put("mode", "FULL_DOCUMENT");
        row.put("source_text", source);
        row.put("sections_json", json.stringify(List.of(
                section("project", "项目经历", "使用Redis缓存商品详情。", "使用 Redis 缓存商品详情，并校验更新一致性。"),
                section("skills", "技能", "熟悉Java。", "熟悉Java。"))));
        when(mapper.lockOptimization("id", "user")).thenReturn(row);
        when(mapper.accept(eq("id"), anyString(), anyString(), anyString())).thenReturn(1);
        when(mapper.optimization("id", "user")).thenReturn(row);

        service.accept("user", "id", "/api/v1/resume-optimizations/id/accept", "key", Map.of("sections", List.of(
                Map.of("sectionKey", "project", "title", "项目经历", "content", "使用 Redis 缓存商品详情，并校验更新一致性。"),
                Map.of("sectionKey", "skills", "title", "技能", "content", "熟悉Java。"))));

        ArgumentCaptor<String> fullText = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> fullJson = ArgumentCaptor.forClass(String.class);
        verify(mapper).accept(eq("id"), anyString(), fullText.capture(), fullJson.capture());
        assertTrue(fullText.getValue().contains("并校验更新一致性"));
        assertTrue(fullText.getValue().contains("教育背景：本科。"));
        assertTrue(fullText.getValue().contains("技能：熟悉Java。"));
        var blocks = json.parse(fullJson.getValue()).get("blocks");
        assertEquals(3, blocks.size());
        assertFalse(blocks.get(0).get("changed").asBoolean());
        assertTrue(blocks.get(1).get("changed").asBoolean());
    }

    @Test
    void exportUsesConfirmedFullDocumentInsteadOfAcceptedSections() throws Exception {
        Map<String, Object> row = optimizationRow("ACCEPTED");
        row.put("title", "定向简历");
        row.put("accepted_sections_json", "[]");
        row.put("full_document_json", json.stringify(Map.of("text", "教育背景：本科。项目经历：使用 Redis 缓存并校验一致性。",
                "blocks", List.of(
                        Map.of("text", "教育背景：本科。", "changed", false),
                        Map.of("text", "项目经历：使用 Redis 缓存并校验一致性。", "changed", true)))));
        when(mapper.optimization("id", "user")).thenReturn(row);

        ResumeOptimizationService.ExportContent content = service.export("user", "id", "PDF");

        try (PDDocument document = Loader.loadPDF(content.bytes())) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("校验一致性"));
            assertTrue(text.contains("教育背景：本科。"));
        }
    }

    @Test
    void exportDiagnosisMarksReadOrderUnverifiedWhenOriginalFileUnavailable() throws Exception {
        Map<String, Object> row = row("READY", assessmentJson());
        row.put("read_order_verified", 0);
        row.put("text_layout_version", FileStorageService.LEGACY_LAYOUT_VERSION);
        when(mapper.diagnosis("id", "user")).thenReturn(row);

        ResumeOptimizationService.ExportContent content = service.exportDiagnosis("user", "id");

        try (PDDocument document = Loader.loadPDF(content.bytes())) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("阅读顺序：未复核"), "历史报告应明确标注阅读顺序未复核");
        }
    }

    @Test
    void exportDiagnosisMarksReadOrderVerifiedWhenSnapshotConfirmed() throws Exception {
        Map<String, Object> row = row("READY", assessmentJson());
        row.put("read_order_verified", 1);
        row.put("text_layout_version", FileStorageService.PDF_LAYOUT_VERSION);
        when(mapper.diagnosis("id", "user")).thenReturn(row);

        ResumeOptimizationService.ExportContent content = service.exportDiagnosis("user", "id");

        try (PDDocument document = Loader.loadPDF(content.bytes())) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("阅读顺序：已按页面位置复核"));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedDiagnosisDocument() {
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(tasks).create(eq("user"), eq("POST"), anyString(), anyString(), any(), eq("RESUME_DIAGNOSE"),
                eq("RESUME_DIAGNOSIS"), anyString(), any(), payload.capture(), any());
        return (Map<String, Object>) payload.getValue().get("document");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedOptimizeDocument() {
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(tasks).create(eq("user"), eq("POST"), anyString(), anyString(), any(), eq("RESUME_OPTIMIZE"),
                eq("RESUME_OPTIMIZATION"), anyString(), any(), payload.capture(), any());
        return (Map<String, Object>) payload.getValue().get("document");
    }

    private Map<String, Object> version(String versionId, String text, String layoutVersion) {
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("id", versionId);
        version.put("status", "PARSED");
        version.put("extracted_text", text);
        version.put("storage_key", "user/" + versionId);
        version.put("mime_type", "application/pdf");
        version.put("file_name", "cv.pdf");
        version.put("size_bytes", 1024);
        version.put("text_layout_version", layoutVersion);
        return version;
    }

    private Map<String, Object> confirmedVersion(String versionId, String text) {
        Map<String, Object> version = version(versionId, text, FileStorageService.LEGACY_LAYOUT_VERSION);
        version.put("status", "CONFIRMED");
        return version;
    }

    private Map<String, Object> job(String jobId) {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", jobId);
        job.put("status", "ACTIVE");
        job.put("requirements_json", "{}");
        job.put("jd_text", "负责 Java 后端开发");
        job.put("job_version", 1);
        return job;
    }

    private Map<String, Object> diagnosis(String versionId, String sourceText) {
        Map<String, Object> diagnosis = new LinkedHashMap<>();
        diagnosis.put("resume_version_id", versionId);
        diagnosis.put("status", "READY");
        diagnosis.put("items_json", "[]");
        diagnosis.put("source_text", sourceText);
        diagnosis.put("text_layout_version", FileStorageService.PDF_LAYOUT_VERSION);
        diagnosis.put("read_order_verified", 1);
        return diagnosis;
    }

    private Map<String, Object> row(String status, String assessmentJson) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "id");
        row.put("resume_version_id", "version");
        row.put("status", status);
        row.put("items_json", itemsJson(false));
        row.put("assessment_json", assessmentJson);
        row.put("created_at", java.time.Instant.now());
        return row;
    }

    private Map<String, Object> optimizationRow(String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "id");
        row.put("resume_version_id", "version");
        row.put("job_id", "job");
        row.put("status", status);
        row.put("created_at", java.time.Instant.now());
        return row;
    }

    private Map<String, Object> section(String key, String title, String original, String suggested) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("sectionKey", key);
        section.put("title", title);
        section.put("originalText", original);
        section.put("suggestedText", suggested);
        section.put("sourceQuotes", List.of());
        return section;
    }

    private String itemsJson(boolean withCheck) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "item-1");
        item.put("priority", "IMPORTANT");
        if (withCheck) {
            item.put("dimensionCode", "EXPERIENCE_PROJECTS");
            item.put("checkCode", "personalRole");
        }
        item.put("title", "补充量化结果");
        item.put("issue", "缺少结果");
        item.put("quote", "使用 Redis 缓存");
        item.put("rationale", "无法判断成效");
        item.put("suggestion", "补充真实指标");
        return json.stringify(List.of(item));
    }

    private String assessmentJson() {
        List<Map<String, Object>> dimensions = List.of(
                dimension("EXPERIENCE_PROJECTS", 18, "使用 Redis 缓存"),
                dimension("SKILLS_EVIDENCE", 12, "使用 Redis 缓存"),
                dimension("CONTENT_COMPLETENESS", 14, "使用 Redis 缓存"),
                dimension("STRUCTURE_CLARITY", 10, "使用 Redis 缓存"),
                dimension("EXPRESSION_PROFESSIONALISM", 9, "使用 Redis 缓存"));
        Map<String, Object> strength = new LinkedHashMap<>();
        strength.put("title", "经历表述具体");
        strength.put("quote", "使用 Redis 缓存");
        strength.put("description", "该段经历描述了具体技术场景。");
        Map<String, Object> assessment = new LinkedHashMap<>();
        assessment.put("ruleVersion", "resume-quality-v1");
        assessment.put("totalScore", 63);
        assessment.put("dimensions", dimensions);
        assessment.put("strengths", List.of(strength));
        assessment.put("assessedAt", "2026-11-20T12:00:00Z");
        return json.stringify(assessment);
    }

    private Map<String, Object> dimension(String code, int score, String quote) {
        Map<String, Object> dimension = new LinkedHashMap<>();
        dimension.put("code", code);
        dimension.put("score", score);
        dimension.put("rationale", "评分理由说明。");
        dimension.put("quote", quote);
        return dimension;
    }
}
