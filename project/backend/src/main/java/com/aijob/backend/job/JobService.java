package com.aijob.backend.job;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    static final int RESUME_COMPARISON_TEXT_LIMIT = 30_000;
    private static final Pattern INTERNSHIP_WORD = Pattern.compile("\\bintern(ship)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern GRADUATION_YEAR = Pattern.compile("\\d{4}\\s*届");
    private static final Pattern AI_WORD = Pattern.compile("\\bai\\b", Pattern.CASE_INSENSITIVE);
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
        Map<String, Object> detail = jobVo(row, userId);
        detail.put("revisions", mapper.revisions(jobId, userId).stream().map(revision -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("versionNo", revision.get("version_no"));
            result.put("cityCode", revision.get("city_code"));
            result.put("createdAt", view.value(revision.get("created_at")));
            result.put("requirements", revision.get("requirements_json") == null ? null
                    : json.map(String.valueOf(revision.get("requirements_json"))));
            Map<String, Object> meta = revision.get("analysis_meta_json") == null ? Map.of()
                    : json.map(String.valueOf(revision.get("analysis_meta_json")));
            result.put("reason", meta.getOrDefault("reason", "原始自动分析"));
            result.put("sourceQuote", meta.get("sourceQuote"));
            return result;
        }).toList());
        return detail;
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
        boolean alreadyConfirmed = row.get("analysis_meta_json") != null
                && Boolean.TRUE.equals(json.map(String.valueOf(row.get("analysis_meta_json"))).get("confirmedByUser"));
        if (alreadyConfirmed && row.get("requirements_json") != null && java.util.Objects.equals(cityCode, row.get("city_code"))
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

    @Transactional
    public Map<String, Object> saveToPersonal(String userId, String jobId) {
        Map<String, Object> row = mapper.job(jobId, userId);
        if (row == null) {
            throw BusinessException.notFound();
        }
        String sourceType = String.valueOf(row.get("source_type"));
        if ("USER_INPUT".equals(sourceType) || "SAVED_SEARCH".equals(sourceType)) {
            return savedPersonalResult(jobId, false);
        }
        if (!"ACTIVE".equals(String.valueOf(row.get("status"))) || row.get("requirements_json") == null) {
            throw BusinessException.conflict("只能保存已完成分析的岗位", "INVALID_STATE");
        }
        String existing = mapper.savedPersonalJobId(userId, jobId);
        if (existing != null) {
            return savedPersonalResult(existing, false);
        }
        String newJobId = UUID.randomUUID().toString();
        String sourceId = UUID.randomUUID().toString();
        String sourceName = row.get("source_name") == null ? "AI 搜索" : String.valueOf(row.get("source_name"));
        mapper.insertSavedSource(sourceId, sourceName);
        mapper.insertSavedJob(newJobId, sourceId, userId,
                row.get("source_url") == null ? null : String.valueOf(row.get("source_url")));
        int version = ((Number) row.get("job_version")).intValue();
        if (mapper.copyJobVersion(UUID.randomUUID().toString(), newJobId, jobId, version) != 1) {
            throw BusinessException.conflict("岗位版本已变化，请刷新后重试", "VERSION_CONFLICT");
        }
        return savedPersonalResult(newJobId, true);
    }

    private Map<String, Object> savedPersonalResult(String jobId, boolean created) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", jobId);
        result.put("created", created);
        return result;
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
        String selectedResumeId = Data.uuid(body, "resumeVersionId", false);
        boolean comparison = selectedResumeId != null;
        String resumeId = comparison ? selectedResumeId : (String) profile.get("activeResumeVersionId");
        if (!comparison && (resumeId == null || mapperRequirementsMissing(profile))) {
            throw BusinessException.conflict("请先确认简历并完整保存求职偏好", "INVALID_STATE");
        }
        Map<String, Object> resume = comparison ? resumes.versionWithResume(resumeId, userId) : resumes.version(resumeId, userId);
        if (comparison && (resume == null || !Set.of("PARSED", "CONFIRMED").contains(String.valueOf(resume.get("status")))
                || !(resume.get("extracted_text") instanceof String text) || text.isBlank())) {
            throw BusinessException.conflict("请选择已解析且有原文的简历", "INVALID_STATE");
        }
        if (!comparison && (resume == null || !"CONFIRMED".equals(String.valueOf(resume.get("status"))))) {
            throw BusinessException.conflict("当前生效简历未确认", "INVALID_STATE");
        }
        if (comparison && Data.list(body, "jobIds", true, 1).size() != 1) {
            throw BusinessException.validation("jobIds", "请选择一个目标岗位");
        }
        List<Map<String, Object>> jobs = candidates(userId, body);
        boolean includeUnknown = Data.bool(body, "includeUnknown", true);
        List<Map<String, Object>> candidates = jobs.stream().map(row -> agentCandidate(row, userId)).toList();
        Set<String> personalSkills = new LinkedHashSet<>();
        boolean hasPersonalJob = false;
        for (Map<String, Object> candidate : candidates) {
            if (!Boolean.TRUE.equals(candidate.get("personalOwned"))) continue;
            hasPersonalJob = true;
            if (candidate.get("requirements") instanceof Map<?, ?> requirements && requirements.get("skills") instanceof List<?> skills) {
                for (Object skill : skills) personalSkills.add(String.valueOf(skill));
            }
        }
        var snapshot = abilityService.matchSnapshot(userId, personalSkills);
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> inputProfile = new LinkedHashMap<>(profile);
        if (comparison) {
            inputProfile.put("activeResumeVersionId", resumeId);
            inputProfile.put("facts", resume.get("confirmed_facts_json") == null
                    ? Map.of() : json.map(String.valueOf(resume.get("confirmed_facts_json"))));
            String resumeText = (String) resume.get("extracted_text");
            int end = Math.min(resumeText.length(), RESUME_COMPARISON_TEXT_LIMIT);
            if (end < resumeText.length() && Character.isHighSurrogate(resumeText.charAt(end - 1))) end--;
            payload.put("resumeText", resumeText.substring(0, end));
        }
        payload.put("profile", inputProfile);
        payload.put("skillSnapshot", profilesSkillSnapshots(snapshot.skills()));
        payload.put("candidates", candidates);
        if (!comparison && hasPersonalJob) payload.put("resumeQuotes", confirmedResumeQuotes(resume));
        String matchId = UUID.randomUUID().toString();
        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("profileVersion", actual);
        versions.put("resumeVersionId", resumeId);
        List<Map<String, Object>> jobSnapshots = jobs.stream().map(row -> jobVo(row, userId)).toList();
        Map<String, Object> batchSnapshot = new LinkedHashMap<>(Map.of(
                "includeUnknown", includeUnknown, "jobs", jobSnapshots, "scoreEvidenceIds", snapshot.evidenceIds()));
        if (comparison) {
            batchSnapshot.put("resumeName", resume.get("title"));
            batchSnapshot.put("resumeVersionNo", resume.get("version_no"));
            batchSnapshot.put("resumeTruncated", ((String) resume.get("extracted_text")).length() > RESUME_COMPARISON_TEXT_LIMIT);
        }
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
        Map<String, Object> snapshot = batch.get("input_snapshot_json") instanceof String input
                ? json.map(input) : Map.of();
        boolean comparison = snapshot.get("resumeName") != null;
        if (comparison) {
            result.put("resumeVersionId", batch.get("resume_version_id"));
            result.put("resumeName", snapshot.get("resumeName"));
            result.put("resumeVersionNo", snapshot.get("resumeVersionNo"));
            result.put("resumeTruncated", snapshot.get("resumeTruncated"));
            if (snapshot.get("jobs") instanceof List<?> jobs && !jobs.isEmpty() && jobs.getFirst() instanceof Map<?, ?> job) {
                result.put("targetTitle", job.get("title"));
            }
        }
        List<Map<String, Object>> items = new ArrayList<>();
        if (includeItems && "READY".equals(String.valueOf(batch.get("status")))) {
            boolean includeUnknown = Boolean.TRUE.equals(snapshot.get("includeUnknown"));
            for (Map<String, Object> item : mapper.items(String.valueOf(batch.get("id")))) {
                String eligibility = String.valueOf(item.get("eligibility_status"));
                if (!comparison && !includeUnknown && "UNKNOWN".equals(eligibility)) continue;
                Map<String, Object> detail = matchItemVo(item);
                if (!comparison && "FAIL".equals(eligibility) && !(detail.get("source") instanceof Map<?, ?> source
                        && "USER_INPUT".equals(source.get("type")))) {
                    continue;
                }
                items.add(detail);
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
        result.put("requirementEvidence", snapshot.get("requirementEvidence") == null ? List.of()
                : snapshot.get("requirementEvidence"));
        result.put("requirementEvidenceCoverage", snapshot.get("requirementEvidenceCoverage"));
        result.put("scoreVersion", "match-v1-trial");
        return result;
    }

    public Map<String, Object> matchEvidence(String userId, String matchId, String itemId, String skillCode) {
        Map<String, Object> item = mapper.matchItem(itemId, userId);
        if (item == null || !matchId.equals(String.valueOf(item.get("batch_id")))) {
            throw BusinessException.notFound();
        }
        List<Map<String, Object>> gaps = item.get("gaps_json") == null ? List.of()
                : json.convert(json.parse(String.valueOf(item.get("gaps_json"))), List.class);
        Map<String, Object> gap = gaps.stream()
                .filter(entry -> skillCode.equals(String.valueOf(entry.get("skillCode"))))
                .findFirst().orElse(null);
        if (gap == null) {
            throw BusinessException.validation("skillCode", "该技能不属于此匹配条目");
        }
        List<String> ids = stringIds(gap.get("evidenceIds"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skillCode", skillCode);
        if (ids.isEmpty()) {
            result.put("evidence", List.of());
            result.put("reason", "该技能暂无可用评分样本，完成训练或面试后再查看");
            return result;
        }
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Map<String, Object> row : abilityService.evidenceByIds(userId, ids)) {
            rows.put(String.valueOf(row.get("id")), row);
        }
        List<String> learningIds = new ArrayList<>();
        List<String> interviewIds = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> row = rows.get(id);
            if (row == null || row.get("source_id") == null) {
                continue;
            }
            String sourceId = String.valueOf(row.get("source_id"));
            String type = String.valueOf(row.get("source_type"));
            if ("LEARNING_ANSWER".equals(type)) {
                learningIds.add(sourceId);
            } else if ("INTERVIEW_ANSWER".equals(type)) {
                interviewIds.add(sourceId);
            }
        }
        Map<String, Map<String, Object>> learning = index(abilityService.learningAnswerDetails(learningIds));
        Map<String, Map<String, Object>> interview = index(abilityService.interviewMessageDetails(interviewIds));
        List<String> questionIds = new ArrayList<>();
        for (Map<String, Object> row : interview.values()) {
            if (row.get("question_message_id") != null) {
                questionIds.add(String.valueOf(row.get("question_message_id")));
            }
        }
        Map<String, Map<String, Object>> questions = index(abilityService.interviewMessageDetails(questionIds));
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (String id : ids) {
            evidence.add(evidenceVo(id, rows.get(id), learning, interview, questions));
        }
        result.put("evidence", evidence);
        return result;
    }

    private Map<String, Object> evidenceVo(String id, Map<String, Object> row,
                                           Map<String, Map<String, Object>> learning,
                                           Map<String, Map<String, Object>> interview,
                                           Map<String, Map<String, Object>> questions) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("evidenceId", id);
        if (row == null) {
            vo.put("available", false);
            vo.put("reason", "评分记录已不存在");
            return vo;
        }
        vo.put("sourceType", row.get("source_type"));
        vo.put("sourceId", row.get("source_id"));
        vo.put("score", row.get("score"));
        vo.put("scoredAt", view.value(row.get("created_at")));
        boolean available = "VALID".equals(String.valueOf(row.get("status")));
        String sourceId = row.get("source_id") == null ? "" : String.valueOf(row.get("source_id"));
        String question = null;
        String answer = null;
        if ("LEARNING_ANSWER".equals(String.valueOf(row.get("source_type")))) {
            Map<String, Object> detail = learning.get(sourceId);
            if (detail != null) {
                question = text(detail.get("stem"));
                answer = text(detail.get("content"));
            }
        } else if ("INTERVIEW_ANSWER".equals(String.valueOf(row.get("source_type")))) {
            Map<String, Object> detail = interview.get(sourceId);
            if (detail != null) {
                answer = text(detail.get("content"));
                if (detail.get("question_message_id") != null) {
                    Map<String, Object> questionRow = questions.get(String.valueOf(detail.get("question_message_id")));
                    if (questionRow != null) {
                        question = text(questionRow.get("content"));
                    }
                }
            }
        }
        if (!available) {
            vo.put("reason", "该评分记录已撤销");
        } else if (question == null || answer == null) {
            available = false;
            vo.put("reason", "来源记录无法读取");
        }
        vo.put("available", available);
        vo.put("question", question);
        vo.put("answerExcerpt", excerpt(answer, 300));
        return vo;
    }

    private List<String> stringIds(Object value) {
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

    private Map<String, Map<String, Object>> index(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if (row.get("id") != null) {
                result.put(String.valueOf(row.get("id")), row);
            }
        }
        return result;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String excerpt(String value, int max) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max) + "…";
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
        boolean ai = value.contains("人工智能") || AI_WORD.matcher(value).find();
        if (value.contains("agent") || value.contains("智能体")) return "AI_AGENT_DEVELOPER";
        if (value.contains("rag") || value.contains("检索增强")) return "RAG_APPLICATION_DEVELOPER";
        if (value.contains("prompt") || value.contains("提示词")) return "PROMPT_ENGINEER";
        if (ai && value.contains("算法")) return "AI_ALGORITHM_ENGINEER";
        if (value.contains("大模型") || value.contains("llm")) return "LLM_APPLICATION_DEVELOPER";
        if (value.contains("nlp") || value.contains("自然语言")) return "NLP_ALGORITHM_ENGINEER";
        if (value.contains("计算机视觉") || value.contains("图像识别")) return "COMPUTER_VISION_ENGINEER";
        if (value.contains("推荐算法") || value.contains("推荐系统")) return "RECOMMENDATION_ALGORITHM";
        if (value.contains("机器学习") || value.contains("machine learning")) return "MACHINE_LEARNING_ENGINEER";
        if (value.contains("深度学习")) return "DEEP_LEARNING_ENGINEER";
        if (ai) return "AI_APPLICATION_DEVELOPER";
        if (value.contains("devops")) return "DEVOPS_ENGINEER";
        if (value.contains("sre") || value.contains("可靠性")) return "SRE_ENGINEER";
        if (value.contains("云计算") || value.contains("cloud")) return "CLOUD_ENGINEER";
        if (value.contains("网络安全") || value.contains("security")) return "SECURITY_ENGINEER";
        if (value.contains("android") || value.contains("安卓")) return "ANDROID_DEVELOPER";
        if (value.contains("ios") || value.contains("苹果")) return "IOS_DEVELOPER";
        if (value.contains("flutter") || value.contains("dart")) return "FLUTTER_DEVELOPER";
        if (value.contains("vue")) return "VUE_DEVELOPER";
        if (value.contains("react")) return "REACT_DEVELOPER";
        if (value.contains("前端") || value.contains("frontend")) return "FRONTEND";
        if (value.contains("数据库") || value.contains("dba")) return "DATABASE_DEVELOPER";
        if (value.contains("嵌入式") || value.contains("embedded")) return "EMBEDDED_ENGINEER";
        if (value.contains("游戏") || value.contains("game")) return "GAME_DEVELOPER";
        if (value.contains("区块链") || value.contains("blockchain")) return "BLOCKCHAIN_DEVELOPER";
        if (value.contains("产品经理") || value.contains("product manager")) return "TECHNICAL_PRODUCT_MANAGER";
        if (value.contains("解决方案") || value.contains("售前")) return "SOLUTION_ENGINEER";
        if (value.contains("golang") || value.contains("go 语言")) return "GO_BACKEND";
        if (value.contains("node") || value.contains("nodejs")) return "NODEJS_BACKEND";
        if (value.contains("c++") || value.contains("cpp")) return "CPP_DEVELOPER";
        if (value.contains("python") && value.contains("后端")) return "PYTHON_BACKEND";
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
            throw BusinessException.conflict("暂无可信岗位候选，请选择一个已分析的个人 JD", "NO_MATCH_CANDIDATES");
        }
        return jobs;
    }

    private Map<String, Object> agentCandidate(Map<String, Object> row, String userId) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("jobId", row.get("id"));
        candidate.put("jobVersion", row.get("job_version"));
        candidate.put("title", row.get("title"));
        candidate.put("jdText", row.get("jd_text"));
        candidate.put("requirements", json.map(String.valueOf(row.get("requirements_json"))));
        candidate.put("personalOwned", "PRIVATE".equals(String.valueOf(row.get("visibility")))
                && "USER_INPUT".equals(row.get("source_type"))
                && row.get("owner_user_id") != null
                && userId.equals(String.valueOf(row.get("owner_user_id"))));
        return candidate;
    }

    private List<String> confirmedResumeQuotes(Map<String, Object> resume) {
        if (!(resume.get("extracted_text") instanceof String source) || resume.get("confirmed_facts_json") == null) {
            return List.of();
        }
        Map<String, Object> facts = json.map(String.valueOf(resume.get("confirmed_facts_json")));
        Set<String> quotes = new LinkedHashSet<>();
        int remaining = 8000;
        for (String field : List.of("skills", "internships", "awards")) {
            if (!(facts.get(field) instanceof List<?> entries)) continue;
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> fact) || !Boolean.TRUE.equals(fact.get("confirmed"))
                        || !(fact.get("quote") instanceof String raw)) continue;
                String quote = raw.strip();
                if (!quote.isEmpty() && quote.length() <= 1000 && quote.length() <= remaining
                        && source.contains(quote) && quotes.add(quote)) {
                    remaining -= quote.length();
                }
            }
        }
        return List.copyOf(quotes);
    }

    private List<Map<String, Object>> profilesSkillSnapshots(List<Map<String, Object>> snapshots) {
        return snapshots.stream().map(row -> {
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
