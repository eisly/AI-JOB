package com.aijob.backend.job;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.CommonResult;
import com.aijob.backend.common.Data;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.common.PageResult;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.resume.ResumeMapper;
import com.aijob.backend.task.TaskService;

@Service
public class JobService {

    private static final Pattern INTERNSHIP_WORD = Pattern.compile("\\bintern(ship)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern GRADUATION_YEAR = Pattern.compile("\\d{4}\\s*届");
    private static final Map<String, String> CITY_CODES = Map.of(
            "北京", "110100", "上海", "310100", "杭州", "330100", "广州", "440100",
            "深圳", "440300", "南京", "320100", "成都", "510100", "武汉", "420100");
    private static final String CITY_NAMES = "北京|上海|杭州|广州|深圳|南京|成都|武汉";
    private static final Pattern JD_CITY = Pattern.compile(
            "(?:工作地点|工作城市|办公地点|职位地点|工作地址)\\s*[:：]\\s*((?:" + CITY_NAMES
                    + ")(?:市)?(?:\\s*[、,，/及和]\\s*(?:" + CITY_NAMES + ")(?:市)?)*)");
    private static final Pattern CITY_NAME = Pattern.compile(CITY_NAMES);

    private final JobMapper mapper;
    private final ResumeMapper resumes;
    private final ProfileService profiles;
    private final TaskService tasks;
    private final JsonService json;
    private final ApiView view;
    private final com.aijob.backend.ability.AbilityService abilityService;

    public JobService(JobMapper mapper, ResumeMapper resumes, ProfileService profiles, TaskService tasks,
                      JsonService json, ApiView view, com.aijob.backend.ability.AbilityService abilityService) {
        this.mapper = mapper;
        this.resumes = resumes;
        this.profiles = profiles;
        this.tasks = tasks;
        this.json = json;
        this.view = view;
        this.abilityService = abilityService;
    }

    public PageResult<Map<String, Object>> jobs(String userId, Integer current, Integer size, String q,
                                                 String cityCode, String candidateType) {
        int page = Data.page(current);
        int pageSize = Data.size(size);
        long offset = Data.offset(current, size);
        if (q != null && q.length() > 100) {
            throw BusinessException.validation("q", "搜索内容不能超过 100 字符");
        }
        List<Map<String, Object>> records = mapper.publicPage(userId, q, cityCode, candidateType, pageSize, offset)
                .stream().map(row -> jobVo(row, userId)).toList();
        return new PageResult<>(mapper.publicCount(q, cityCode, candidateType), records, page, pageSize);
    }

    public PageResult<Map<String, Object>> imports(String userId, Integer current, Integer size) {
        int page = Data.page(current);
        int pageSize = Data.size(size);
        List<Map<String, Object>> records = mapper.privatePage(userId, pageSize, Data.offset(current, size))
                .stream().map(row -> jobVo(row, userId)).toList();
        return new PageResult<>(mapper.privateCount(userId), records, page, pageSize);
    }

    public Map<String, Object> job(String userId, String jobId) {
        Map<String, Object> row = mapper.job(jobId, userId);
        if (row == null) {
            throw BusinessException.notFound();
        }
        return jobVo(row, userId);
    }

    @Transactional
    public Map<String, Object> correctPrivateJob(String userId, String jobId, Map<String, Object> body) {
        Map<String, Object> row = mapper.lockJob(jobId, userId);
        if (row == null) throw BusinessException.notFound();
        if (!"PRIVATE".equals(row.get("visibility")) || !userId.equals(row.get("owner_user_id"))
                || !Set.of("ACTIVE", "FAILED").contains(String.valueOf(row.get("status")))) {
            throw BusinessException.conflict("只能核对自己已完成或分析失败的个人 JD", "INVALID_STATE");
        }
        Integer expected = Data.integer(body, "expectedJobVersion", null, 1, Integer.MAX_VALUE);
        int current = ((Number) row.get("job_version")).intValue();
        if (expected == null || expected != current) throw BusinessException.versionConflict(current);
        String reason = Data.text(body, "reason", true, 500);
        String quote = Data.text(body, "sourceQuote", true, 1000);
        String jdText = String.valueOf(row.get("jd_text"));
        if (!jdText.contains(quote)) throw BusinessException.validation("sourceQuote", "依据必须是 JD 原文片段");
        if (!body.containsKey("cityCode")) throw BusinessException.validation("cityCode", "请核对工作城市");
        String cityCode = body.get("cityCode") == null ? null : Data.text(body, "cityCode", false, 12);
        if (cityCode != null && !cityCode.matches("\\d{6}")) {
            throw BusinessException.validation("cityCode", "城市编码非法");
        }
        if (!body.containsKey("skillCodes")) throw BusinessException.validation("skillCodes", "请核对技能要求");
        List<Object> rawSkills = Data.list(body, "skillCodes", false, 50);
        List<String> skills = new ArrayList<>();
        for (Object raw : rawSkills) {
            if (!(raw instanceof String code)) throw BusinessException.validation("skillCodes", "技能编码非法");
            profiles.requireSkill(userId, code);
            if (!skills.contains(code)) skills.add(code);
        }
        Map<String, Object> requirements = row.get("requirements_json") == null
                ? new LinkedHashMap<>() : json.map(String.valueOf(row.get("requirements_json")));
        requirements.put("skills", skills);
        requirements.putIfAbsent("requiredSkillLevels", new LinkedHashMap<>());
        requirements.putIfAbsent("quotes", new LinkedHashMap<>());
        for (String field : List.of("educationMin", "graduationYearMin", "graduationYearMax", "experienceMonthsMin")) {
            requirements.putIfAbsent(field, null);
        }
        if (requirements.get("requiredSkillLevels") instanceof Map<?, ?> levels) {
            Map<String, Object> retained = new LinkedHashMap<>();
            for (String code : skills) if (levels.containsKey(code)) retained.put(code, levels.get(code));
            requirements.put("requiredSkillLevels", retained);
        }
        if (row.get("requirements_json") != null && java.util.Objects.equals(cityCode, row.get("city_code"))
                && json.canonical(requirements).equals(json.canonical(json.map(String.valueOf(row.get("requirements_json")))))) {
            throw BusinessException.validation("body", "提取字段没有变化");
        }
        String metadata = json.stringify(Map.of("confirmedByUser", true, "reason", reason, "sourceQuote", quote));
        if (mapper.insertCorrectedVersion(UUID.randomUUID().toString(), String.valueOf(row.get("job_version_id")),
                cityCode, json.stringify(requirements), metadata) != 1
                || mapper.advancePrivateVersion(jobId, userId, current) != 1) {
            throw BusinessException.conflict("岗位版本已变化，请重新加载", "VERSION_CONFLICT");
        }
        return jobVo(mapper.job(jobId, userId), userId);
    }

    public ResponseEntity<CommonResult<Object>> importJob(String userId, String route, String key,
                                                          Map<String, Object> body) {
        String title = Data.text(body, "title", true, 100);
        String company = Data.text(body, "companyName", true, 100);
        String jdText = Data.text(body, "jdText", true, 30000);
        if (jdText.length() < 50) {
            throw BusinessException.validation("jdText", "jdText 长度必须为 50..30000");
        }
        String sourceUrl = Data.url(body, "sourceUrl");
        String jobId = UUID.randomUUID().toString();
        String sourceId = UUID.randomUUID().toString();
        String versionId = UUID.randomUUID().toString();
        String combined = title + " " + jdText;
        String roleCode = roleCode(combined);
        String type = candidateType(combined);
        String cityCode = cityCodeFromJd(jdText);
        Map<String, Object> payload = Map.of("jobId", jobId, "jobVersion", 1, "jdText", jdText,
                "skillCatalog", profiles.agentSkillCatalog(userId));
        return tasks.create(userId, "POST", route, key, body, "JD_ANALYZE", "JOB", jobId,
                Map.of("jobVersion", 1), payload, taskId -> {
                    mapper.insertPrivateSource(sourceId);
                    mapper.insertPrivateJob(jobId, sourceId, userId, sourceUrl);
                    mapper.insertJobVersion(versionId, jobId, title, company, cityCode, roleCode, type,
                            jdText, json.sha256(jdText));
                });
    }

    static String cityCodeFromJd(String jdText) {
        Matcher matcher = JD_CITY.matcher(jdText);
        String city = null;
        while (matcher.find()) {
            Matcher names = CITY_NAME.matcher(matcher.group(1));
            while (names.find()) {
                String found = CITY_CODES.get(names.group());
                if (city != null && !city.equals(found)) return null;
                city = found;
            }
        }
        return city;
    }

    @Transactional
    public boolean favorite(String userId, String jobId) {
        if (mapper.job(jobId, userId) == null) {
            throw BusinessException.notFound();
        }
        mapper.favorite(UUID.randomUUID().toString(), userId, jobId);
        return true;
    }

    @Transactional
    public boolean unfavorite(String userId, String jobId) {
        if (mapper.job(jobId, userId) == null) {
            throw BusinessException.notFound();
        }
        mapper.unfavorite(userId, jobId);
        return true;
    }

    public PageResult<Map<String, Object>> favorites(String userId, Integer current, Integer size) {
        int page = Data.page(current);
        int pageSize = Data.size(size);
        List<Map<String, Object>> records = mapper.favoritePage(userId, pageSize, Data.offset(current, size))
                .stream().map(row -> jobVo(row, userId)).toList();
        return new PageResult<>(mapper.favoriteCount(userId), records, page, pageSize);
    }

    public ResponseEntity<CommonResult<Object>> match(String userId, String route, String key,
                                                      Map<String, Object> body) {
        Map<String, Object> profile = profiles.get(userId);
        long expected = requiredVersion(body, "expectedProfileVersion");
        long actual = ((Number) profile.get("profileVersion")).longValue();
        if (expected != actual) {
            throw BusinessException.versionConflict(actual);
        }
        String resumeId = profile.get("activeResumeVersionId") == null ? null : String.valueOf(profile.get("activeResumeVersionId"));
        if (resumeId == null || mapperRequirementsMissing(profile)) {
            throw BusinessException.conflict("请先确认简历并完整保存求职偏好", "INVALID_STATE");
        }
        Map<String, Object> resume = resumes.version(resumeId, userId);
        if (resume == null || !"CONFIRMED".equals(String.valueOf(resume.get("status")))) {
            throw BusinessException.conflict("当前生效简历未确认", "INVALID_STATE");
        }
        List<Map<String, Object>> jobs = candidates(userId, body);
        boolean includeUnknown = Data.bool(body, "includeUnknown", true);
        List<Map<String, Object>> candidates = jobs.stream().map(this::agentCandidate).toList();
        List<Map<String, Object>> snapshots = profilesSkillSnapshots(userId);
        Map<String, Object> payload = Map.of("profile", profile, "skillSnapshot", snapshots, "candidates", candidates);
        String matchId = UUID.randomUUID().toString();
        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("profileVersion", actual);
        versions.put("resumeVersionId", resumeId);
        List<Map<String, Object>> jobSnapshots = jobs.stream().map(row -> jobVo(row, userId)).toList();
        Map<String, Object> batchSnapshot = Map.of("includeUnknown", includeUnknown, "jobs", jobSnapshots);
        return tasks.create(userId, "POST", route, key, body, "JOB_MATCH", "JOB_MATCH", matchId,
                versions, payload, taskId -> mapper.insertBatch(matchId, userId, taskId, actual, resumeId,
                        json.stringify(batchSnapshot)));
    }

    public Map<String, Object> match(String userId, String matchId) {
        Map<String, Object> batch = mapper.batch(matchId, userId);
        if (batch == null) {
            throw BusinessException.notFound();
        }
        return batchVo(batch, true);
    }

    public PageResult<Map<String, Object>> matches(String userId, Integer current, Integer size) {
        int page = Data.page(current);
        int pageSize = Data.size(size);
        List<Map<String, Object>> records = mapper.batchPage(userId, pageSize, Data.offset(current, size))
                .stream().map(row -> batchVo(row, false)).toList();
        return new PageResult<>(mapper.batchCount(userId), records, page, pageSize);
    }

    public Map<String, Object> batchVo(Map<String, Object> batch, boolean includeItems) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", batch.get("id"));
        result.put("taskId", batch.get("task_id"));
        result.put("profileVersion", batch.get("profile_version"));
        result.put("status", batch.get("status"));
        long currentProfile = ((Number) profiles.get(String.valueOf(batch.get("user_id"))).get("profileVersion")).longValue();
        boolean stale = currentProfile != ((Number) batch.get("profile_version")).longValue()
                || mapper.staleItems(String.valueOf(batch.get("id"))) > 0;
        result.put("isStale", stale);
        result.put("createdAt", view.value(batch.get("created_at")));
        List<Map<String, Object>> items = new ArrayList<>();
        if (includeItems && "READY".equals(String.valueOf(batch.get("status")))) {
            boolean includeUnknown = Boolean.TRUE.equals(json.map(String.valueOf(batch.get("input_snapshot_json"))).get("includeUnknown"));
            for (Map<String, Object> item : mapper.items(String.valueOf(batch.get("id")))) {
                String eligibility = String.valueOf(item.get("eligibility_status"));
                if (!"FAIL".equals(eligibility) && (includeUnknown || !"UNKNOWN".equals(eligibility))) {
                    items.add(matchItemVo(item));
                }
            }
        }
        result.put("items", items);
        return result;
    }

    public Map<String, Object> matchItemVo(Map<String, Object> item) {
        Map<String, Object> result = view.map(item, "source_snapshot_json", "batch_status", "input_snapshot_json",
                "user_id", "profile_version", "resume_version_id", "batch_id", "rank_no", "eligibility_status");
        Map<String, Object> snapshot = json.map(String.valueOf(item.get("source_snapshot_json")));
        result.put("title", snapshot.get("title"));
        result.put("companyName", snapshot.get("companyName"));
        result.put("cityCode", snapshot.get("cityCode"));
        result.put("candidateType", snapshot.get("candidateType"));
        result.put("source", snapshot.get("source"));
        result.put("sourceUrl", snapshot.get("sourceUrl"));
        result.put("verifiedAt", snapshot.get("verifiedAt"));
        result.put("scoreVersion", "match-v1-trial");
        return result;
    }

    public Map<String, Object> jobVo(Map<String, Object> row, String userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("jobVersion", row.get("job_version"));
        result.put("title", row.get("title"));
        result.put("companyName", row.get("company_name"));
        result.put("cityCode", row.get("city_code"));
        result.put("candidateType", row.get("candidate_type"));
        result.put("roleCode", row.get("role_code"));
        result.put("minMonthlySalaryCents", row.get("min_monthly_salary_cents"));
        result.put("maxMonthlySalaryCents", row.get("max_monthly_salary_cents"));
        result.put("jdText", row.get("jd_text"));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("id", row.get("source_id"));
        source.put("name", row.get("source_name"));
        source.put("type", row.get("source_type"));
        source.put("baseUrl", row.get("source_base_url"));
        source.put("verificationStatus", row.get("verification_status"));
        source.put("verifiedAt", view.value(row.get("source_verified_at")));
        result.put("source", source);
        result.put("sourceUrl", row.get("source_url"));
        result.put("status", row.get("status"));
        result.put("verifiedAt", view.value(row.get("verified_at")));
        result.put("requirements", row.get("requirements_json") == null ? null
                : json.convert(json.parse(String.valueOf(row.get("requirements_json"))), Object.class));
        Map<String, Object> evidence = new LinkedHashMap<>();
        Matcher cityMatch = JD_CITY.matcher(String.valueOf(row.get("jd_text")));
        if (cityMatch.find()) evidence.put("city", cityMatch.group());
        if (row.get("analysis_meta_json") != null) {
            Map<String, Object> meta = json.map(String.valueOf(row.get("analysis_meta_json")));
            if (Boolean.TRUE.equals(meta.get("confirmedByUser"))) {
                evidence.put("confirmedQuote", meta.get("sourceQuote"));
                evidence.put("correctionReason", meta.get("reason"));
            }
        }
        result.put("fieldEvidence", evidence);
        result.put("isFavorite", mapper.isFavorite(userId, String.valueOf(row.get("id"))) > 0);
        return result;
    }

    static String candidateType(String text) {
        String value = text == null ? "" : text.toLowerCase();
        if (value.contains("实习") || INTERNSHIP_WORD.matcher(value).find()) {
            return "INTERNSHIP";
        }
        if (value.contains("校招") || value.contains("校园招聘") || value.contains("应届")
                || value.contains("毕业生") || GRADUATION_YEAR.matcher(value).find()) {
            return "CAMPUS";
        }
        return "EXPERIENCED";
    }

    public String roleCode(String text) {
        String value = text.toLowerCase();
        if (value.contains("前端") || value.contains("frontend")) return "FRONTEND";
        if (value.contains("算法") || value.contains("algorithm")) return "ALGORITHM";
        if (value.contains("测试") || value.contains("qa")) return "TEST";
        if (value.contains("数据分析") || value.contains("data")) return "DATA";
        if (value.contains("java") || value.contains("后端")) return "JAVA_BACKEND";
        return null;
    }

    private List<Map<String, Object>> candidates(String userId, Map<String, Object> body) {
        List<Object> ids = Data.list(body, "jobIds", false, 20);
        List<Map<String, Object>> jobs = new ArrayList<>();
        if (ids.isEmpty()) {
            jobs.addAll(mapper.defaultCandidates());
        } else {
            for (Object raw : ids) {
                String id = Data.uuid(String.valueOf(raw), "jobIds");
                Map<String, Object> job = mapper.job(id, userId);
                if (job == null || !"ACTIVE".equals(String.valueOf(job.get("status"))) || job.get("requirements_json") == null) {
                    throw BusinessException.conflict("候选岗位不存在、未分析或不可见", "INVALID_STATE");
                }
                jobs.add(job);
            }
        }
        if (jobs.isEmpty()) {
            throw BusinessException.conflict("没有可匹配的岗位候选", "INVALID_STATE");
        }
        return jobs;
    }

    private Map<String, Object> agentCandidate(Map<String, Object> row) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("jobId", row.get("id"));
        candidate.put("jobVersion", row.get("job_version"));
        candidate.put("title", row.get("title"));
        candidate.put("jdText", row.get("jd_text"));
        candidate.put("requirements", json.map(String.valueOf(row.get("requirements_json"))));
        return candidate;
    }

    private List<Map<String, Object>> profilesSkillSnapshots(String userId) {
        return abilityService.snapshots(userId).stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("skillCode", row.get("skill_code"));
            item.put("score", row.get("score"));
            item.put("sampleCount", row.get("sample_count"));
            return item;
        }).toList();
    }

    @SuppressWarnings("unchecked")
    private boolean mapperRequirementsMissing(Map<String, Object> profile) {
        List<Object> cities = (List<Object>) profile.getOrDefault("cities", List.of());
        return profile.get("targetRole") == null || profile.get("candidateType") == null || cities.isEmpty();
    }

    private long requiredVersion(Map<String, Object> body, String field) {
        Integer value = Data.integer(body, field, null, 1, Integer.MAX_VALUE);
        if (value == null) throw BusinessException.validation(field, field + " 不能为空");
        return value;
    }
}
