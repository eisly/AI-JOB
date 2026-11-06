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
import com.aijob.backend.resume.ResumeMapper;
import com.aijob.backend.task.IdempotencyService;
import com.aijob.backend.task.TaskService;

@Service
public class ResumeOptimizationService {

    private final ResumeOptimizationMapper mapper;
    private final ResumeMapper resumes;
    private final JobMapper jobs;
    private final TaskService tasks;
    private final IdempotencyService idempotency;
    private final ResumeExportService exports;
    private final JsonService json;
    private final ApiView view;

    public ResumeOptimizationService(ResumeOptimizationMapper mapper, ResumeMapper resumes, JobMapper jobs,
                                     TaskService tasks, IdempotencyService idempotency, ResumeExportService exports,
                                     JsonService json, ApiView view) {
        this.mapper = mapper;
        this.resumes = resumes;
        this.jobs = jobs;
        this.tasks = tasks;
        this.idempotency = idempotency;
        this.exports = exports;
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
        Map<String, Object> payload = Map.of("document", document(version));
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
        Map<String, Object> diagnosisPayload = null;
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
        }
        int jobVersion = ((Number) job.get("job_version")).intValue();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("document", document(version));
        payload.put("jobId", jobId);
        payload.put("jobVersion", jobVersion);
        payload.put("jdText", job.get("jd_text"));
        payload.put("diagnosis", diagnosisPayload);
        String optimizationId = UUID.randomUUID().toString();
        String acceptedDiagnosisId = diagnosisId;
        return tasks.create(userId, "POST", route, key, body, "RESUME_OPTIMIZE", "RESUME_OPTIMIZATION", optimizationId,
                Map.of("resumeVersionId", resumeVersionId, "jobVersion", jobVersion), payload,
                taskId -> mapper.insertOptimization(optimizationId, userId, resumeVersionId, jobId,
                        acceptedDiagnosisId, taskId));
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
        if (!"ACCEPTED".equals(String.valueOf(row.get("status"))) || row.get("accepted_sections_json") == null) {
            throw BusinessException.conflict("仅已采纳优化稿可以导出", "INVALID_STATE");
        }
        String normalized = format == null ? "" : format.strip().toUpperCase(Locale.ROOT);
        List<Map<String, Object>> sections = json.convert(json.parse(String.valueOf(row.get("accepted_sections_json"))), List.class);
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
        if (mapper.accept(optimizationId, json.stringify(accepted)) == 0) {
            throw BusinessException.conflict("优化稿状态已变化", "INVALID_STATE");
        }
        return optimizationVo(mapper.optimization(optimizationId, userId));
    }

    private Map<String, Object> document(Map<String, Object> version) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("versionId", version.get("id"));
        document.put("fileName", version.get("file_name"));
        document.put("mimeType", version.get("mime_type"));
        document.put("sizeBytes", version.get("size_bytes"));
        document.put("text", version.get("extracted_text"));
        return document;
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
        result.put("resumeVersionId", row.get("resume_version_id"));
        result.put("status", row.get("status"));
        result.put("items", row.get("items_json") == null ? List.of()
                : json.convert(json.parse(String.valueOf(row.get("items_json"))), Object.class));
        result.put("createdAt", view.value(row.get("created_at")));
        return result;
    }

    private Map<String, Object> optimizationVo(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("resumeVersionId", row.get("resume_version_id"));
        result.put("jobId", row.get("job_id"));
        result.put("diagnosisId", row.get("diagnosis_id"));
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
        result.put("acceptedAt", view.value(row.get("accepted_at")));
        result.put("createdAt", view.value(row.get("created_at")));
        return result;
    }

    public record ExportContent(byte[] bytes, String fileName, String mediaType) {
    }
}