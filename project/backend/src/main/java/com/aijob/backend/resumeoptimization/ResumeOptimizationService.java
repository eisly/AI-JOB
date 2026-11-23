package com.aijob.backend.resumeoptimization;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.CommonResult;
import com.aijob.backend.common.Data;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.common.PageResult;
import com.aijob.backend.job.JobMapper;
import com.aijob.backend.resume.FileStorageService;
import com.aijob.backend.resume.ResumeMapper;
import com.aijob.backend.task.IdempotencyService;
import com.aijob.backend.task.TaskService;

@Service
public class ResumeOptimizationService {

    private static final Map<String, Object> ASSESSMENT_RULES = ResumeAssessmentRules.currentPayload();
    private static final List<String> PRIORITIES = List.of("IMPORTANT", "SUGGESTION", "NOTICE");
    private static final Map<String, String> PRIORITY_LABELS = Map.of(
            "IMPORTANT", "高优先级", "SUGGESTION", "中优先级", "NOTICE", "低优先级");
    private final ResumeOptimizationMapper mapper;
    private final ResumeMapper resumes;
    private final JobMapper jobs;
    private final TaskService tasks;
    private final IdempotencyService idempotency;
    private final ResumeExportService exports;
    private final FileStorageService files;
    private final JsonService json;
    private final ApiView view;

    public ResumeOptimizationService(ResumeOptimizationMapper mapper, ResumeMapper resumes, JobMapper jobs,
                                     TaskService tasks, IdempotencyService idempotency, ResumeExportService exports,
                                     FileStorageService files, JsonService json, ApiView view) {
        this.mapper = mapper;
        this.resumes = resumes;
        this.jobs = jobs;
        this.tasks = tasks;
        this.idempotency = idempotency;
        this.exports = exports;
        this.files = files;
        this.json = json;
        this.view = view;
    }

    public ResponseEntity<CommonResult<Object>> diagnose(String userId, String route, String key,
                                                          Map<String, Object> body) {
        ResponseEntity<CommonResult<Object>> replay = tasks.replay(userId, "POST", route, key, body);
        if (replay != null) {
            return replay;
        }
        String resumeVersionId = Data.uuid(body, "resumeVersionId", true);
        Map<String, Object> version = resumes.version(resumeVersionId, userId);
        if (version == null) {
            throw BusinessException.notFound();
        }
        if (!Set.of("PARSED", "CONFIRMED").contains(String.valueOf(version.get("status")))
                || version.get("extracted_text") == null
                || String.valueOf(version.get("extracted_text")).isBlank()) {
            throw BusinessException.conflict("简历尚无可用解析原文", "INVALID_STATE");
        }
        String diagnosisId = UUID.randomUUID().toString();
        TextSnapshot snapshot = snapshot(userId, version);
        Map<String, Object> payload = Map.of("document",
                document(version, snapshot.text(), snapshot.layoutVersion(), snapshot.readOrderVerified()),
                "assessmentRules", ASSESSMENT_RULES);
        return tasks.create(userId, "POST", route, key, body, "RESUME_DIAGNOSE", "RESUME_DIAGNOSIS", diagnosisId,
                Map.of("resumeVersionId", resumeVersionId), payload,
                taskId -> mapper.insertDiagnosis(diagnosisId, userId, resumeVersionId, taskId));
    }

    public Map<String, Object> diagnosis(String userId, String diagnosisId) {
        Map<String, Object> row = mapper.diagnosis(diagnosisId, userId);
        if (row == null) {
            throw BusinessException.notFound();
        }
        return diagnosisVo(row);
    }

    public PageResult<Map<String, Object>> diagnoses(String userId, String resumeVersionId,
                                                      Integer current, Integer size) {
        int page = Data.page(current);
        int pageSize = Data.size(size);
        List<Map<String, Object>> records = mapper.diagnosisPage(userId, resumeVersionId, pageSize,
                Data.offset(current, size)).stream().map(this::diagnosisVo).toList();
        return new PageResult<>(mapper.diagnosisCount(userId, resumeVersionId), records, page, pageSize);
    }

    public ResponseEntity<CommonResult<Object>> optimize(String userId, String route, String key,
                                                          Map<String, Object> body) {
        ResponseEntity<CommonResult<Object>> replay = tasks.replay(userId, "POST", route, key, body);
        if (replay != null) {
            return replay;
        }
        String resumeVersionId = Data.uuid(body, "resumeVersionId", true);
        String jobId = Data.uuid(body, "jobId", true);
        String diagnosisId = Data.uuid(body, "diagnosisId", false);
        String mode = Data.text(body, "mode", false, 16);
        if (mode == null || mode.isBlank()) {
            mode = "SECTIONS";
        }
        if (!Set.of("SECTIONS", "FULL_DOCUMENT").contains(mode)) {
            throw BusinessException.validation("mode", "mode 仅支持 SECTIONS 或 FULL_DOCUMENT");
        }
        Integer expectedJobVersion = Data.integer(body, "jobVersion", null, 1, Integer.MAX_VALUE);
        Map<String, Object> version = resumes.version(resumeVersionId, userId);
        Map<String, Object> job = jobs.job(jobId, userId);
        if (version == null || job == null) {
            throw BusinessException.notFound();
        }
        if (!"CONFIRMED".equals(String.valueOf(version.get("status")))
                || version.get("extracted_text") == null
                || String.valueOf(version.get("extracted_text")).isBlank()) {
            throw BusinessException.conflict("仅已确认且具有解析原文的简历可优化", "INVALID_STATE");
        }
        if (!"ACTIVE".equals(String.valueOf(job.get("status"))) || job.get("requirements_json") == null
                || job.get("jd_text") == null || String.valueOf(job.get("jd_text")).isBlank()) {
            throw BusinessException.conflict("岗位必须可见且已完成分析", "INVALID_STATE");
        }
        int currentJobVersion = ((Number) job.get("job_version")).intValue();
        if ("FULL_DOCUMENT".equals(mode)) {
            if (!"PRIVATE".equals(String.valueOf(job.get("visibility")))
                    || job.get("owner_user_id") == null
                    || !userId.equals(String.valueOf(job.get("owner_user_id")))) {
                throw BusinessException.conflict("完整简历模式仅支持本人导入的个人 JD", "INVALID_STATE");
            }
            if (expectedJobVersion != null && expectedJobVersion != currentJobVersion) {
                throw BusinessException.conflict("岗位 JD 已更新，请刷新后重新生成", "INVALID_STATE");
            }
        }
        Map<String, Object> diagnosisPayload = null;
        String text = String.valueOf(version.get("extracted_text"));
        String layoutVersion = layoutVersion(version.get("text_layout_version"));
        boolean readOrderVerified = false;
        if (diagnosisId != null) {
            Map<String, Object> diagnosis = mapper.diagnosis(diagnosisId, userId);
            if (diagnosis == null) {
                throw BusinessException.notFound();
            }
            if (!resumeVersionId.equals(String.valueOf(diagnosis.get("resume_version_id")))
                    || !"READY".equals(String.valueOf(diagnosis.get("status")))) {
                throw BusinessException.conflict("诊断不属于该简历或尚未就绪", "INVALID_STATE");
            }
            diagnosisPayload = Map.of("items", diagnosisAgentItems(diagnosis));
            if (diagnosis.get("source_text") != null
                    && !String.valueOf(diagnosis.get("source_text")).isBlank()) {
                text = String.valueOf(diagnosis.get("source_text"));
                layoutVersion = layoutVersion(diagnosis.get("text_layout_version"));
                readOrderVerified = truthy(diagnosis.get("read_order_verified"));
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        String companyName = job.get("company_name") == null ? null : String.valueOf(job.get("company_name"));
        String jobTitle = job.get("title") == null ? null : String.valueOf(job.get("title"));
        payload.put("document", document(version, text, layoutVersion, readOrderVerified));
        payload.put("jobId", jobId);
        payload.put("jobVersion", currentJobVersion);
        payload.put("jdText", job.get("jd_text"));
        payload.put("companyName", companyName);
        payload.put("jobTitle", jobTitle);
        payload.put("diagnosis", diagnosisPayload);
        payload.put("mode", mode);
        String optimizationId = UUID.randomUUID().toString();
        String acceptedDiagnosisId = diagnosisId;
        String snapshotMode = mode;
        String snapshotText = text;
        return tasks.create(userId, "POST", route, key, body, "RESUME_OPTIMIZE", "RESUME_OPTIMIZATION", optimizationId,
                Map.of("resumeVersionId", resumeVersionId, "jobVersion", currentJobVersion), payload,
                taskId -> mapper.insertOptimization(optimizationId, userId, resumeVersionId, jobId,
                        acceptedDiagnosisId, snapshotMode, currentJobVersion, companyName, jobTitle, snapshotText, taskId));
    }

    public Map<String, Object> optimization(String userId, String optimizationId) {
        Map<String, Object> row = mapper.optimization(optimizationId, userId);
        if (row == null) {
            throw BusinessException.notFound();
        }
        return optimizationVo(row);
    }

    public PageResult<Map<String, Object>> optimizations(String userId, String resumeVersionId, String jobId,
                                                          Integer current, Integer size) {
        int page = Data.page(current);
        int pageSize = Data.size(size);
        List<Map<String, Object>> records = mapper.optimizationPage(userId, resumeVersionId, jobId, pageSize,
                Data.offset(current, size)).stream().map(this::optimizationVo).toList();
        return new PageResult<>(mapper.optimizationCount(userId, resumeVersionId, jobId), records, page, pageSize);
    }

    public ResponseEntity<CommonResult<Object>> accept(String userId, String optimizationId, String route, String key,
                                                        Map<String, Object> body) {
        return idempotency.execute(userId, "POST", route, key, body, 200,
                () -> acceptSections(userId, optimizationId, body));
    }

    public ExportContent export(String userId, String optimizationId, String format) {
        Map<String, Object> row = mapper.optimization(optimizationId, userId);
        if (row == null) {
            throw BusinessException.notFound();
        }
        if (!"ACCEPTED".equals(String.valueOf(row.get("status")))
                || (row.get("accepted_sections_json") == null && row.get("full_document_json") == null)) {
            throw BusinessException.conflict("仅已采纳优化稿可以导出", "INVALID_STATE");
        }
        String normalized = format == null ? "" : format.strip().toUpperCase(Locale.ROOT);
        boolean fullDocument = row.get("full_document_json") != null;
        List<Map<String, Object>> sections = fullDocument
                ? fullDocumentSections(json.convert(json.parse(String.valueOf(row.get("full_document_json"))), Map.class))
                : json.convert(json.parse(String.valueOf(row.get("accepted_sections_json"))), List.class);
        String title = row.get("title") == null ? "优化简历" : String.valueOf(row.get("title"));
        if ("PDF".equals(normalized)) {
            return new ExportContent(exports.pdf(title, sections), "resume-" + optimizationId + ".pdf", "application/pdf");
        }
        if ("DOCX".equals(normalized)) {
            return new ExportContent(exports.docx(title, sections), "resume-" + optimizationId + ".docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        }
        throw BusinessException.validation("format", "format 仅支持 PDF 或 DOCX");
    }

    private Map<String, Object> acceptSections(String userId, String optimizationId, Map<String, Object> body) {
        Map<String, Object> row = mapper.lockOptimization(optimizationId, userId);
        if (row == null) {
            throw BusinessException.notFound();
        }
        String status = String.valueOf(row.get("status"));
        if (!Set.of("READY", "ACCEPTED").contains(status) || row.get("sections_json") == null) {
            throw BusinessException.conflict("优化稿尚未就绪", "INVALID_STATE");
        }
        List<Map<String, Object>> original = json.convert(json.parse(String.valueOf(row.get("sections_json"))), List.class);
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> section : original) {
            byKey.put(String.valueOf(section.get("sectionKey")), section);
        }
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> accepted = new ArrayList<>();
        for (Object raw : Data.list(body, "sections", true, 20)) {
            if (!(raw instanceof Map<?, ?>)) {
                throw BusinessException.validation("sections", "采纳段落必须为对象");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> section = (Map<String, Object>) raw;
            String sectionKey = Data.text(section, "sectionKey", true, 64);
            if (!byKey.containsKey(sectionKey) || !seen.add(sectionKey)) {
                throw BusinessException.validation("sections.sectionKey", "段落缺失、重复或不属于原优化稿");
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("sectionKey", sectionKey);
            normalized.put("title", Data.text(section, "title", true, 200));
            normalized.put("content", Data.text(section, "content", true, 10000));
            accepted.add(normalized);
        }
        if (accepted.size() != byKey.size()) {
            throw BusinessException.validation("sections", "必须完整提交全部优化段落");
        }
        if ("ACCEPTED".equals(status)) {
            List<Map<String, Object>> saved = json.convert(json.parse(String.valueOf(row.get("accepted_sections_json"))), List.class);
            if (!json.canonical(saved).equals(json.canonical(accepted))) {
                throw BusinessException.conflict("优化稿已采纳，内容变化需刷新后处理", "INVALID_STATE");
            }
            return optimizationVo(mapper.optimization(optimizationId, userId));
        }
        FullDocument full = buildFullDocument(row, original, accepted);
        if (mapper.accept(optimizationId, json.stringify(accepted),
                full == null ? null : full.text(),
                full == null ? null : json.stringify(Map.of("text", full.text(), "blocks", full.blocks()))) == 0) {
            throw BusinessException.conflict("优化稿状态已变化", "INVALID_STATE");
        }
        return optimizationVo(mapper.optimization(optimizationId, userId));
    }

    private FullDocument buildFullDocument(Map<String, Object> row, List<Map<String, Object>> originals,
                                           List<Map<String, Object>> accepted) {
        if (!"FULL_DOCUMENT".equals(String.valueOf(row.get("mode")))
                || row.get("source_text") == null || String.valueOf(row.get("source_text")).isBlank()) {
            return null;
        }
        String source = String.valueOf(row.get("source_text"));
        Map<String, String> contents = new LinkedHashMap<>();
        for (Map<String, Object> section : accepted) {
            contents.put(String.valueOf(section.get("sectionKey")), String.valueOf(section.get("content")));
        }
        List<Span> candidates = new ArrayList<>();
        for (Map<String, Object> section : originals) {
            String key = String.valueOf(section.get("sectionKey"));
            String content = contents.get(key);
            if (content == null) {
                continue;
            }
            String original = section.get("originalText") == null ? "" : String.valueOf(section.get("originalText"));
            if (original.isBlank() || content.equals(original)) {
                continue;
            }
            int index = source.indexOf(original);
            if (index < 0 || source.indexOf(original, index + 1) >= 0) {
                continue;
            }
            candidates.add(new Span(index, index + original.length(), content));
        }
        candidates.sort(java.util.Comparator.comparingInt(Span::start));
        List<Map<String, Object>> blocks = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int cursor = 0;
        for (Span span : candidates) {
            if (span.start() < cursor) {
                continue;
            }
            if (span.start() > cursor) {
                String plain = source.substring(cursor, span.start());
                blocks.add(Map.of("text", plain, "changed", false));
                text.append(plain);
            }
            blocks.add(Map.of("text", span.content(), "changed", true));
            text.append(span.content());
            cursor = span.end();
        }
        if (cursor < source.length()) {
            String tail = source.substring(cursor);
            blocks.add(Map.of("text", tail, "changed", false));
            text.append(tail);
        }
        if (blocks.isEmpty()) {
            blocks.add(Map.of("text", source, "changed", false));
            text.append(source);
        }
        return new FullDocument(text.toString(), blocks);
    }

    private Map<String, Object> document(Map<String, Object> version, String text, String layoutVersion,
                                         boolean readOrderVerified) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("versionId", version.get("id"));
        document.put("fileName", version.get("file_name"));
        document.put("mimeType", version.get("mime_type"));
        document.put("sizeBytes", version.get("size_bytes"));
        document.put("text", text);
        document.put("textLayoutVersion", layoutVersion);
        document.put("readOrderVerified", readOrderVerified);
        return document;
    }

    private TextSnapshot snapshot(String userId, Map<String, Object> version) {
        String storedText = String.valueOf(version.get("extracted_text"));
        FileStorageService.ExtractedText extracted = files.reExtract(userId,
                version.get("storage_key") == null ? null : String.valueOf(version.get("storage_key")),
                version.get("mime_type") == null ? null : String.valueOf(version.get("mime_type")));
        if (extracted != null && extracted.text() != null && !extracted.text().isBlank()) {
            return new TextSnapshot(extracted.text(), extracted.layoutVersion(), true);
        }
        return new TextSnapshot(storedText, layoutVersion(version.get("text_layout_version")), false);
    }

    private String layoutVersion(Object stored) {
        if (stored == null || String.valueOf(stored).isBlank()) {
            return FileStorageService.LEGACY_LAYOUT_VERSION;
        }
        return String.valueOf(stored);
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }

    private List<Map<String, Object>> diagnosisAgentItems(Map<String, Object> diagnosis) {
        List<Map<String, Object>> items = json.convert(json.parse(String.valueOf(diagnosis.get("items_json"))), List.class);
        return items.stream().map(item -> {
            Map<String, Object> result = new LinkedHashMap<>(item);
            result.remove("id");
            return result;
        }).toList();
    }

    private Map<String, Object> diagnosisVo(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("taskId", row.get("task_id"));
        result.put("resumeVersionId", row.get("resume_version_id"));
        result.put("status", row.get("status"));
        result.put("items", row.get("items_json") == null ? List.of()
                : json.convert(json.parse(String.valueOf(row.get("items_json"))), Object.class));
        result.put("assessment", assessmentVo(row.get("assessment_json")));
        result.put("readOrderVerified", truthy(row.get("read_order_verified")));
        result.put("textLayoutVersion", row.get("text_layout_version"));
        result.put("createdAt", view.value(row.get("created_at")));
        return result;
    }

    private Map<String, Object> assessmentVo(Object assessmentJson) {
        if (assessmentJson == null) {
            return null;
        }
        Map<String, Object> assessment = json.convert(json.parse(String.valueOf(assessmentJson)), Map.class);
        List<Map<String, Object>> dimensions = new ArrayList<>();
        for (Object raw : json.convert(assessment.get("dimensions"), List.class)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> dimension = (Map<String, Object>) raw;
            Map<String, Object> vo = new LinkedHashMap<>(dimension);
            ResumeAssessmentRules.Dimension rule = ResumeAssessmentRules.dimension(String.valueOf(dimension.get("code")));
            if (rule != null) {
                vo.put("label", rule.label());
                vo.put("maxScore", rule.maxScore(String.valueOf(assessment.get("ruleVersion"))));
            }
            dimensions.add(vo);
        }
        Map<String, Object> vo = new LinkedHashMap<>(assessment);
        vo.put("dimensions", dimensions);
        return vo;
    }

    public ExportContent exportDiagnosis(String userId, String diagnosisId) {
        Map<String, Object> row = mapper.diagnosis(diagnosisId, userId);
        if (row == null) {
            throw BusinessException.notFound();
        }
        if (!"READY".equals(String.valueOf(row.get("status"))) || row.get("assessment_json") == null) {
            throw BusinessException.conflict("诊断尚未就绪或没有评分结果", "INVALID_STATE");
        }
        Map<String, Object> assessment = assessmentVo(row.get("assessment_json"));
        List<Map<String, Object>> items = row.get("items_json") == null ? List.of()
                : json.convert(json.parse(String.valueOf(row.get("items_json"))), List.class);
        String fileName = "resume-assessment-" + diagnosisId + ".pdf";
        return new ExportContent(assessmentPdf(row, assessment, items), fileName, "application/pdf");
    }

    @SuppressWarnings("unchecked")
    private byte[] assessmentPdf(Map<String, Object> row, Map<String, Object> assessment,
                                 List<Map<String, Object>> items) {
        StringBuilder summary = new StringBuilder();
        summary.append("简历版本：").append(row.get("resume_version_id")).append('\n');
        summary.append("评分时间：").append(assessment.get("assessedAt")).append('\n');
        summary.append("评分规则：").append(assessment.get("ruleVersion")).append('\n');
        summary.append("总分：").append(assessment.get("totalScore")).append(" / 100").append('\n');
        appendField(summary, "综合评价", assessment.get("summary"));
        summary.append("阅读顺序：").append(truthy(row.get("read_order_verified"))
                ? "已按页面位置复核"
                : "未复核（原文件不可用，使用已保存文本，章节顺序可能不准确）");
        List<Map<String, Object>> sections = new ArrayList<>();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("title", "评分概览");
        header.put("content", summary.toString());
        sections.add(header);
        List<Map<String, Object>> dimensions = (List<Map<String, Object>>) assessment.get("dimensions");
        for (Map<String, Object> dimension : dimensions) {
            StringBuilder content = new StringBuilder();
            content.append("得分：").append(dimension.get("score")).append('/')
                    .append(dimension.get("maxScore")).append('\n');
            content.append("评分理由：").append(dimension.get("rationale")).append('\n');
            if (dimension.get("criteria") instanceof List<?> criteria) {
                for (Object raw : criteria) {
                    Map<?, ?> check = (Map<?, ?>) raw;
                    content.append("  ").append(check.get("label")).append("：")
                            .append(check.get("score")).append('/').append(check.get("maxScore")).append('\n');
                }
            }
            if (dimension.get("quote") != null && !String.valueOf(dimension.get("quote")).isBlank()) {
                content.append("原文依据：").append(dimension.get("quote")).append('\n');
            }
            for (String quote : stringList(dimension.get("quotes"))) {
                content.append("补充依据：").append(quote).append('\n');
            }
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("title", String.valueOf(dimension.get("label")) + "（"
                    + dimension.get("score") + "/" + dimension.get("maxScore") + "）");
            section.put("content", content.toString());
            sections.add(section);
        }
        List<Map<String, Object>> strengths = (List<Map<String, Object>>) assessment.get("strengths");
        if (strengths != null && !strengths.isEmpty()) {
            StringBuilder content = new StringBuilder();
            for (Map<String, Object> strength : strengths) {
                content.append("· ").append(strength.get("title")).append('\n');
                content.append("  原文依据：").append(strength.get("quote")).append('\n');
                for (String quote : stringList(strength.get("quotes"))) {
                    content.append("  补充依据：").append(quote).append('\n');
                }
                appendField(content, "具体做法", strength.get("approach"));
                appendField(content, "本人贡献", strength.get("contribution"));
                appendField(content, "已有结果", strength.get("result"));
                content.append("  ").append(strength.get("description")).append('\n');
            }
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("title", "优势");
            section.put("content", content.toString());
            sections.add(section);
        }
        if (!items.isEmpty()) {
            StringBuilder content = new StringBuilder();
            List<Map<String, Object>> ordered = items.stream().sorted(java.util.Comparator.comparingInt(item -> {
                int index = PRIORITIES.indexOf(String.valueOf(item.get("priority")));
                return index < 0 ? PRIORITIES.size() : index;
            })).toList();
            for (Map<String, Object> item : ordered) {
                content.append("· ");
                String priority = PRIORITY_LABELS.get(String.valueOf(item.get("priority")));
                if (priority != null) {
                    content.append(priority).append(" · ");
                }
                ResumeAssessmentRules.Dimension dimension = ResumeAssessmentRules.dimension(String.valueOf(item.get("dimensionCode")));
                if (dimension != null) {
                    content.append(dimension.label()).append(" · ");
                    if (item.get("checkCode") instanceof String checkCode) {
                        Map<?, ?> check = ResumeAssessmentRules.scoredCheck(assessment, dimension.code(), checkCode);
                        if (!check.isEmpty()) {
                            content.append(check.get("label")).append(' ')
                                    .append(check.get("score")).append('/').append(check.get("maxScore")).append(" · ");
                        }
                    }
                }
                content.append(item.get("title")).append('\n');
                content.append("  问题：").append(item.get("issue")).append('\n');
                content.append("  原文依据：").append(item.get("quote")).append('\n');
                for (String quote : stringList(item.get("quotes"))) {
                    content.append("  补充依据：").append(quote).append('\n');
                }
                appendField(content, "依据", item.get("rationale"));
                appendField(content, "影响", item.get("impact"));
                appendField(content, "修改动作（待本人核对）", item.get("suggestion"));
                appendField(content, "示例（基于原文改写）", item.get("example"));
                List<String> pending = stringList(item.get("pendingVerification"));
                if (!pending.isEmpty()) {
                    content.append("  待核实：").append(String.join("；", pending)).append('\n');
                }
            }
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("title", "优先改进建议（按影响排序）");
            section.put("content", content.toString());
            sections.add(section);
        }
        return exports.pdf("简历评分报告", sections);
    }

    private static void appendField(StringBuilder content, String label, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            content.append("  ").append(label).append("：").append(value).append('\n');
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private Map<String, Object> optimizationVo(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("resumeVersionId", row.get("resume_version_id"));
        result.put("jobId", row.get("job_id"));
        result.put("diagnosisId", row.get("diagnosis_id"));
        result.put("mode", row.get("mode") == null ? "SECTIONS" : row.get("mode"));
        result.put("jobVersion", row.get("job_version"));
        result.put("companyName", row.get("job_company_name"));
        result.put("jobTitle", row.get("job_title"));
        result.put("status", row.get("status"));
        result.put("title", row.get("title"));
        List<Map<String, Object>> sections = row.get("sections_json") == null ? new ArrayList<>()
                : json.convert(json.parse(String.valueOf(row.get("sections_json"))), List.class);
        if (row.get("accepted_sections_json") != null) {
            Map<String, Map<String, Object>> accepted = new LinkedHashMap<>();
            List<Map<String, Object>> saved = json.convert(json.parse(String.valueOf(row.get("accepted_sections_json"))), List.class);
            for (Map<String, Object> section : saved) {
                accepted.put(String.valueOf(section.get("sectionKey")), section);
            }
            for (Map<String, Object> section : sections) {
                Map<String, Object> finalSection = accepted.get(String.valueOf(section.get("sectionKey")));
                if (finalSection != null) {
                    section.put("content", finalSection.get("content"));
                    section.put("title", finalSection.get("title"));
                }
            }
        }
        result.put("sections", sections);
        result.put("missingRequirements", row.get("missing_requirements_json") == null ? List.of()
                : json.convert(json.parse(String.valueOf(row.get("missing_requirements_json"))), Object.class));
        result.put("notes", row.get("notes_json") == null ? List.of()
                : json.convert(json.parse(String.valueOf(row.get("notes_json"))), Object.class));
        result.put("sourceText", row.get("source_text"));
        Map<String, Object> fullDocument = null;
        if (row.get("full_document_json") != null) {
            fullDocument = json.convert(json.parse(String.valueOf(row.get("full_document_json"))), Map.class);
        } else if ("READY".equals(String.valueOf(row.get("status")))) {
            List<Map<String, Object>> suggested = new ArrayList<>();
            for (Map<String, Object> section : sections) {
                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("sectionKey", section.get("sectionKey"));
                candidate.put("title", section.get("title"));
                candidate.put("content", section.get("suggestedText"));
                suggested.add(candidate);
            }
            FullDocument preview = buildFullDocument(row, sections, suggested);
            if (preview != null) {
                fullDocument = Map.of("text", preview.text(), "blocks", preview.blocks());
            }
        }
        result.put("fullDocument", fullDocument);
        result.put("acceptedAt", view.value(row.get("accepted_at")));
        result.put("createdAt", view.value(row.get("created_at")));
        return result;
    }

    private static List<Map<String, Object>> fullDocumentSections(Map<String, Object> document) {
        List<Map<String, Object>> sections = new ArrayList<>();
        Object rawBlocks = document.get("blocks");
        if (!(rawBlocks instanceof List<?> blocks)) {
            return sections;
        }
        for (Object raw : blocks) {
            if (!(raw instanceof Map<?, ?> block) || block.get("text") == null) {
                continue;
            }
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("title", "");
            section.put("content", String.valueOf(block.get("text")));
            sections.add(section);
        }
        return sections;
    }

    public record ExportContent(byte[] bytes, String fileName, String mediaType) {
    }

    private record TextSnapshot(String text, String layoutVersion, boolean readOrderVerified) {
    }

    private record FullDocument(String text, List<Map<String, Object>> blocks) {
    }

    private record Span(int start, int end, String content) {
    }
}
