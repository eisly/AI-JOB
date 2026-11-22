package com.aijob.backend.resume;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.aijob.backend.ability.AbilityService;
import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.CommonResult;
import com.aijob.backend.common.Data;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.common.PageResult;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.task.AiTask;
import com.aijob.backend.task.IdempotencyService;
import com.aijob.backend.task.TaskMapper;
import com.aijob.backend.task.TaskService;

@Service
public class ResumeService {

    private static final Set<String> EDUCATION = Set.of("HIGH_SCHOOL", "ASSOCIATE", "BACHELOR", "MASTER", "DOCTOR");

    private final ResumeMapper mapper;
    private final FileStorageService files;
    private final JsonService json;
    private final ApiView view;
    private final IdempotencyService idempotency;
    private final TaskService tasks;
    private final TaskMapper taskMapper;
    private final ProfileService profiles;
    private final AbilityService ability;
    private final String internalBaseUrl;

    public ResumeService(ResumeMapper mapper, FileStorageService files, JsonService json, ApiView view,
                         IdempotencyService idempotency, TaskService tasks, TaskMapper taskMapper,
                         ProfileService profiles, AbilityService ability,
                         @Value("${app.files.internal-base-url}") String internalBaseUrl) {
        this.mapper = mapper;
        this.files = files;
        this.json = json;
        this.view = view;
        this.idempotency = idempotency;
        this.tasks = tasks;
        this.taskMapper = taskMapper;
        this.profiles = profiles;
        this.ability = ability;
        this.internalBaseUrl = internalBaseUrl;
    }

    public ResponseEntity<CommonResult<Object>> upload(String userId, String route, String key, MultipartFile file,
                                                       String title, String resumeId) {
        String normalizedResumeId = resumeId == null || resumeId.isBlank() ? null : Data.uuid(resumeId, "resumeId");
        if (file.getSize() > 10L * 1024 * 1024) {
            throw BusinessException.payloadTooLarge();
        }
        String fileHash;
        try {
            fileHash = json.sha256(file.getBytes());
        } catch (Exception ex) {
            throw BusinessException.validation("file", "文件读取失败");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("title", title);
        request.put("resumeId", normalizedResumeId);
        request.put("fileSha256", fileHash);
        return idempotency.execute(userId, "POST", route, key, request, 200, () -> {
            String versionId = UUID.randomUUID().toString();
            FileStorageService.StoredFile stored = files.store(userId, versionId, file);
            String actualResumeId = normalizedResumeId;
            int versionNo = 1;
            if (actualResumeId == null) {
                actualResumeId = UUID.randomUUID().toString();
                String actualTitle = title == null || title.isBlank() ? stored.fileName() : title.strip();
                if (actualTitle.length() > 100) {
                    throw BusinessException.validation("title", "title 长度不能超过 100");
                }
                mapper.insertResume(actualResumeId, userId, actualTitle);
            } else {
                Map<String, Object> resume = mapper.lockResume(actualResumeId, userId);
                if (resume == null) {
                    throw BusinessException.notFound();
                }
                versionNo = mapper.maxVersion(actualResumeId) + 1;
            }
            mapper.insertVersion(versionId, actualResumeId, userId, versionNo, stored.fileName(), stored.key(),
                    stored.mimeType(), stored.bytes().length, stored.sha256(), stored.text(), stored.textSha256(),
                    stored.layoutVersion());
            mapper.updateLatest(actualResumeId, versionId);
            Map<String, Object> row = mapper.resume(actualResumeId, userId);
            Map<String, Object> version = mapper.version(versionId, userId);
            return resumeVo(row, version);
        });
    }

    public PageResult<Map<String, Object>> list(String userId, Integer current, Integer size) {
        int page = Data.page(current);
        int pageSize = Data.size(size);
        long offset = Data.offset(current, size);
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> row : mapper.page(userId, pageSize, offset)) {
            Map<String, Object> version = new LinkedHashMap<>(row);
            version.put("id", row.get("version_id"));
            version.put("created_at", row.get("version_created_at"));
            records.add(resumeVo(row, version));
        }
        return new PageResult<>(mapper.count(userId), records, page, pageSize);
    }

    public List<Map<String, Object>> versions(String userId, String resumeId) {
        if (mapper.resume(resumeId, userId) == null) {
            throw BusinessException.notFound();
        }
        return mapper.versions(resumeId, userId).stream().map(this::versionVo).toList();
    }

    public Map<String, Object> version(String userId, String versionId) {
        Map<String, Object> version = mapper.version(versionId, userId);
        if (version == null) {
            throw BusinessException.notFound();
        }
        return versionVo(version);
    }

    public Map<String, Object> download(String userId, String versionId) {
        if (mapper.version(versionId, userId) == null) {
            throw BusinessException.notFound();
        }
        Instant expiresAt = Instant.now().plusSeconds(60);
        String signature = files.signature(userId, versionId, expiresAt);
        String url = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/resume-versions/{versionId}/file")
                .queryParam("expires", expiresAt.getEpochSecond())
                .queryParam("signature", signature).build(versionId).toString();
        return Map.of("url", url, "expiresAt", expiresAt);
    }

    public DownloadContent file(String userId, String versionId, long expires, String signature) {
        Map<String, Object> version = mapper.version(versionId, userId);
        if (version == null) {
            throw BusinessException.notFound();
        }
        Instant expiresAt = Instant.ofEpochSecond(expires);
        String expected = files.signature(userId, versionId, expiresAt);
        if (expiresAt.isBefore(Instant.now()) || signature == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            throw BusinessException.forbidden();
        }
        Resource resource = files.read(userId, String.valueOf(version.get("storage_key")));
        return new DownloadContent(resource, String.valueOf(version.get("file_name")), String.valueOf(version.get("mime_type")));
    }

    /**
     * Agent 内部下载：带 owner 的签名 URL（HMAC 覆盖 owner:versionId:expires，不可伪造），
     * 无需用户 Bearer 认证；用于 RESUME_OCR 任务获取原始 PDF。
     */
    public DownloadContent fileBySignature(String owner, String versionId, long expires, String signature) {
        return file(owner, versionId, expires, signature);
    }

    public ResponseEntity<CommonResult<Object>> parse(String userId, String versionId, String route, String key,
                                                       Boolean reparse) {
        boolean forceReparse = Boolean.TRUE.equals(reparse);
        Map<String, Object> request = forceReparse
                ? Map.of("versionId", versionId, "reparse", true)
                : Map.of("versionId", versionId);
        ResponseEntity<CommonResult<Object>> replay = tasks.replay(userId, "POST", route, key, request);
        if (replay != null) {
            return replay;
        }
        Map<String, Object> version = mapper.version(versionId, userId);
        if (version == null) {
            throw BusinessException.notFound();
        }
        String status = String.valueOf(version.get("status"));
        AiTask latest = taskMapper.latestResourceTask("RESUME_VERSION", versionId);
        if (latest != null && Set.of("QUEUED", "RUNNING", "CANCEL_REQUESTED").contains(latest.getStatus())) {
            throw BusinessException.taskInProgress(latest.getId());
        }

        if (!forceReparse && Set.of("PARSED", "CONFIRMED").contains(status) && latest != null && "SUCCEEDED".equals(latest.getStatus())) {
            return idempotency.executeTask(userId, "POST", route, key, request,
                    () -> new IdempotencyService.ActionResult(latest.getId(), tasks.vo(latest)));
        }
        // reparse 仅开放给待确认版本；已确认版本通过事实纠正或追加版本处理。
        if (!Set.of("UPLOADED", "FAILED").contains(status) && !(forceReparse && "PARSED".equals(status))) {
            throw BusinessException.conflict("当前简历版本不能解析", "INVALID_STATE");
        }
        return createParseTask(userId, versionId, version, route, key, request);
    }

    /**
     * 按版本当前文本状态创建解析或 OCR 任务：
     * PDF 无文字层（文本为空或仅页码标记）时先走 RESUME_OCR，识别完成后再自动接续解析。
     */
    private ResponseEntity<CommonResult<Object>> createParseTask(String userId, String versionId,
                                                                  Map<String, Object> version, String route,
                                                                  String key, Map<String, Object> request) {
        String text = version.get("extracted_text") == null ? null : String.valueOf(version.get("extracted_text"));
        String mimeType = String.valueOf(version.get("mime_type"));
        boolean pdfWithoutText = "application/pdf".equals(mimeType)
                && (text == null || text.replaceAll("\\[page:\\d+]", "").isBlank());
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("versionId", versionId);
        document.put("fileName", version.get("file_name"));
        document.put("mimeType", mimeType);
        document.put("sizeBytes", version.get("size_bytes"));
        if (pdfWithoutText) {
            document.put("downloadUrl", internalDownloadUrl(userId, versionId));
            Map<String, Object> payload = Map.of("document", document);
            return tasks.create(userId, "POST", route, key, request, "RESUME_OCR", "RESUME_VERSION", versionId,
                    Map.of("resumeVersionId", versionId), payload, null, taskId -> { });
        }
        document.put("text", text);
        Map<String, Object> payload = Map.of("document", document, "skillCatalog", profiles.agentSkillCatalog(userId));
        return tasks.create(userId, "POST", route, key, request, "RESUME_PARSE", "RESUME_VERSION", versionId,
                Map.of("resumeVersionId", versionId), payload, null,
                taskId -> mapper.updateStatus(versionId, "PARSING"));
    }

    /**
     * OCR 保存成功后的自动接续解析：复用已识别文本直接创建 RESUME_PARSE 任务。
     * 接续失败时版本保持 UPLOADED，用户可手动发起解析。
     */
    public void startParseAfterOcr(String userId, String versionId, String ocrTaskId) {
        Map<String, Object> version = mapper.version(versionId, userId);
        if (version == null || !"UPLOADED".equals(String.valueOf(version.get("status")))) {
            return;
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("versionId", versionId);
        document.put("fileName", version.get("file_name"));
        document.put("mimeType", version.get("mime_type"));
        document.put("sizeBytes", version.get("size_bytes"));
        document.put("text", version.get("extracted_text"));
        Map<String, Object> payload = Map.of("document", document, "skillCatalog", profiles.agentSkillCatalog(userId));
        tasks.create(userId, "POST", "/internal/ai/ocr-followup/" + ocrTaskId, UUID.randomUUID().toString(),
                Map.of("versionId", versionId), "RESUME_PARSE", "RESUME_VERSION", versionId,
                Map.of("resumeVersionId", versionId), payload, null,
                taskId -> mapper.updateStatus(versionId, "PARSING"));
    }

    private String internalDownloadUrl(String userId, String versionId) {
        Instant expiresAt = Instant.now().plusSeconds(900);
        String signature = files.signature(userId, versionId, expiresAt);
        return internalBaseUrl + "/api/v1/resume-versions/" + versionId
                + "/file?owner=" + userId
                + "&expires=" + expiresAt.getEpochSecond() + "&signature=" + signature;
    }

    public ResponseEntity<CommonResult<Object>> confirm(String userId, String versionId, String route, String key,
                                                        Map<String, Object> body) {
        return idempotency.execute(userId, "POST", route, key, body, 200, () -> confirmFacts(userId, versionId, body));
    }

    public ResponseEntity<CommonResult<Object>> correct(String userId, String versionId, String route, String key,
                                                         Map<String, Object> body) {
        return idempotency.execute(userId, "POST", route, key, body, 200, () -> correctFacts(userId, versionId, body));
    }

    @Transactional
    public Map<String, Object> correctFacts(String userId, String versionId, Map<String, Object> body) {
        Map<String, Object> version = mapper.lockVersion(versionId, userId);
        if (version == null) throw BusinessException.notFound();
        if (!"CONFIRMED".equals(version.get("status"))) {
            throw BusinessException.conflict("仅已确认的简历事实可以纠正", "INVALID_STATE");
        }
        String reason = Data.text(body, "reason", true, 500);
        Map<String, Object> facts = validateFacts(userId, Data.object(body, "facts", true));
        Map<String, Object> profile = profiles.locked(userId);
        long actual = ((Number) profile.get("profile_version")).longValue();
        if (requiredVersion(body, "expectedProfileVersion") != actual) throw BusinessException.versionConflict(actual);
        if (!versionId.equals(profile.get("active_resume_version_id"))) {
            throw BusinessException.conflict("只能纠正当前生效的简历版本", "INVALID_STATE");
        }
        String previous = String.valueOf(version.get("confirmed_facts_json"));
        if (json.canonical(json.map(previous)).equals(json.canonical(facts))) {
            throw BusinessException.validation("facts", "事实没有变化");
        }
        int revision = mapper.maxFactRevision(versionId);
        if (revision == 0) {
            mapper.insertFactRevision(UUID.randomUUID().toString(), versionId, 1, null, previous, "首次确认的事实");
            revision = 1;
        }
        if (mapper.correctFacts(versionId, json.stringify(facts)) == 0) {
            throw BusinessException.conflict("简历版本状态已变化", "INVALID_STATE");
        }
        profiles.confirmFacts(userId, versionId, facts, actual);
        ability.replaceResumeClaims(userId, versionId, facts);
        mapper.insertFactRevision(UUID.randomUUID().toString(), versionId, revision + 1, actual + 1,
                json.stringify(facts), reason);
        return profiles.get(userId);
    }

    public List<Map<String, Object>> factRevisions(String userId, String versionId) {
        if (mapper.version(versionId, userId) == null) throw BusinessException.notFound();
        return mapper.factRevisions(versionId).stream().map(row -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("revisionNo", row.get("revision_no"));
            result.put("profileVersion", row.get("profile_version"));
            result.put("facts", json.map(String.valueOf(row.get("facts_json"))));
            result.put("reason", row.get("reason"));
            result.put("createdAt", view.value(row.get("created_at")));
            return result;
        }).toList();
    }

    @Transactional
    public Map<String, Object> confirmFacts(String userId, String versionId, Map<String, Object> body) {
        Map<String, Object> version = mapper.lockVersion(versionId, userId);
        if (version == null) {
            throw BusinessException.notFound();
        }
        Map<String, Object> facts = validateFacts(userId, Data.object(body, "facts", true));
        String status = String.valueOf(version.get("status"));
        if ("CONFIRMED".equals(status)) {
            String confirmed = String.valueOf(version.get("confirmed_facts_json"));
            if (json.canonical(json.map(confirmed)).equals(json.canonical(facts))) {
                return profiles.get(userId);
            }
            throw BusinessException.conflict("已确认版本不能修改事实，请上传新版本", "INVALID_STATE");
        }
        if (!"PARSED".equals(status)) {
            throw BusinessException.conflict("仅 PARSED 简历版本可以确认", "INVALID_STATE");
        }
        Map<String, Object> profile = profiles.locked(userId);
        long expected = requiredVersion(body, "expectedProfileVersion");
        long actual = ((Number) profile.get("profile_version")).longValue();
        if (expected != actual) {
            throw BusinessException.versionConflict(actual);
        }
        if (mapper.confirm(versionId, json.stringify(facts)) == 0) {
            throw BusinessException.conflict("简历版本状态已变化", "INVALID_STATE");
        }
        profiles.confirmFacts(userId, versionId, facts, expected);
        ability.replaceResumeClaims(userId, versionId, facts);
        return profiles.get(userId);
    }

    @Transactional
    public boolean delete(String userId, String resumeId) {
        Map<String, Object> resume = mapper.lockResume(resumeId, userId);
        if (resume == null) {
            throw BusinessException.notFound();
        }
        if (mapper.activeReferences(resumeId) > 0) {
            throw BusinessException.conflict("简历存在活动引用，不能删除", "INVALID_STATE");
        }
        Map<String, Object> profile = profiles.locked(userId);
        String activeVersion = profile.get("active_resume_version_id") == null ? null
                : String.valueOf(profile.get("active_resume_version_id"));
        List<Map<String, Object>> versions = mapper.versions(resumeId, userId);
        mapper.deleteVersions(resumeId);
        mapper.deleteResume(resumeId, userId);
        for (Map<String, Object> version : versions) {
            ability.revokeResumeVersion(userId, String.valueOf(version.get("id")));
        }
        if (activeVersion != null) {
            profiles.clearActive(userId, activeVersion);
        }
        return true;
    }

    public Map<String, Object> versionVo(Map<String, Object> row) {
        Map<String, Object> result = view.map(row, "storage_key", "extracted_text", "extracted_text_sha256",
                "version_created_at", "version_id", "parsed_facts_json", "confirmed_facts_json");
        String status = String.valueOf(row.get("status"));
        Object facts = "CONFIRMED".equals(status) ? row.get("confirmed_facts_json") : row.get("parsed_facts_json");
        result.put("facts", facts == null ? null : json.convert(json.parse(String.valueOf(facts)), Object.class));
        Object parsed = row.get("parsed_facts_json");
        result.put("parsedFacts", parsed == null ? null : json.convert(json.parse(String.valueOf(parsed)), Object.class));
        return result;
    }

    private Map<String, Object> resumeVo(Map<String, Object> resume, Map<String, Object> version) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", resume.get("id"));
        result.put("title", resume.get("title"));
        result.put("latestVersion", versionVo(version));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> validateFacts(String userId, Map<String, Object> input) {
        Map<String, Object> facts = new LinkedHashMap<>();
        String education = input.get("education") == null ? null : String.valueOf(input.get("education"));
        if (education != null && !EDUCATION.contains(education)) {
            throw BusinessException.validation("facts.education", "学历枚举非法");
        }
        facts.put("education", education);
        Integer year = Data.integer(input, "graduationYear", null, 1900, LocalDateTime.now().getYear() + 10);
        Integer months = Data.integer(input, "experienceMonths", null, 0, 600);
        facts.put("graduationYear", year);
        facts.put("experienceMonths", months);
        facts.put("major", input.get("major") == null ? null : Data.text(input, "major", false, 200));
        List<Object> rawSkills = Data.list(input, "skills", false, 50);
        List<Map<String, Object>> skills = new ArrayList<>();
        for (Object raw : rawSkills) {
            if (!(raw instanceof Map<?, ?>)) {
                throw BusinessException.validation("facts.skills", "技能事实必须为对象");
            }
            Map<String, Object> skill = new LinkedHashMap<>((Map<String, Object>) raw);
            String code = Data.text(skill, "skillCode", true, 64);
            profiles.requireSkill(userId, code);
            if (!Boolean.TRUE.equals(skill.get("confirmed"))) {
                throw BusinessException.validation("facts.skills.confirmed", "确认事实的 confirmed 必须为 true");
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("skillCode", code);
            normalized.put("claim", Data.text(skill, "claim", true, 500));
            normalized.put("quote", Data.text(skill, "quote", true, 1000));
            normalized.put("location", Data.text(skill, "location", true, 200));
            normalized.put("confirmed", true);
            skills.add(normalized);
        }
        facts.put("skills", skills);
        facts.put("internships", evidenceFacts(input, "internships"));
        facts.put("awards", evidenceFacts(input, "awards"));
        List<String> warnings = new ArrayList<>();
        for (Object warning : Data.list(input, "warnings", false, 20)) {
            if (!(warning instanceof String value)) {
                throw BusinessException.validation("facts.warnings", "警告必须为字符串");
            }
            warnings.add(value);
        }
        facts.put("warnings", warnings);
        return facts;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> evidenceFacts(Map<String, Object> input, String field) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object raw : Data.list(input, field, false, 20)) {
            if (!(raw instanceof Map<?, ?>)) {
                throw BusinessException.validation("facts." + field, "经历事实必须为对象");
            }
            Map<String, Object> item = (Map<String, Object>) raw;
            if (!Boolean.TRUE.equals(item.get("confirmed"))) {
                throw BusinessException.validation("facts." + field + ".confirmed", "确认事实的 confirmed 必须为 true");
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("claim", Data.text(item, "claim", true, 500));
            normalized.put("quote", Data.text(item, "quote", true, 1000));
            normalized.put("location", Data.text(item, "location", true, 200));
            normalized.put("confirmed", true);
            result.add(normalized);
        }
        return result;
    }

    private long requiredVersion(Map<String, Object> body, String field) {
        Integer value = Data.integer(body, field, null, 1, Integer.MAX_VALUE);
        if (value == null) {
            throw BusinessException.validation(field, field + " 不能为空");
        }
        return value;
    }

    public record DownloadContent(Resource resource, String fileName, String mimeType) {
    }
}
