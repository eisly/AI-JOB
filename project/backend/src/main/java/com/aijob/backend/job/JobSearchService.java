package com.aijob.backend.job;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

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
public class JobSearchService {

    private static final Set<String> CANDIDATE_TYPES = Set.of("INTERNSHIP", "CAMPUS", "EXPERIENCED");
    private static final Set<String> SOURCE_KEYS = Set.of("company_official", "web", "baidu", "tencent", "greenhouse", "lever",
            "liepin", "boss", "zhaopin");

    private final JobSearchMapper mapper;
    private final JobMapper jobs;
    private final ProfileService profiles;
    private final ResumeMapper resumes;
    private final TaskService tasks;
    private final JsonService json;
    private final ApiView view;
    private final String configuredSources;
    private final int perSource;
    private final int maxTotal;
    private final int concurrency;
    private final int timeoutSeconds;

    public JobSearchService(JobSearchMapper mapper, JobMapper jobs, ProfileService profiles, ResumeMapper resumes,
                            TaskService tasks, JsonService json, ApiView view,
                            @Value("${app.job-search.sources:company_official,web,baidu,tencent,greenhouse}") String configuredSources,
                            @Value("${app.job-search.per-source:20}") int perSource,
                            @Value("${app.job-search.max-total:60}") int maxTotal,
                            @Value("${app.job-search.concurrency:4}") int concurrency,
                            @Value("${app.job-search.source-timeout-seconds:12}") int timeoutSeconds) {
        this.mapper = mapper;
        this.jobs = jobs;
        this.profiles = profiles;
        this.resumes = resumes;
        this.tasks = tasks;
        this.json = json;
        this.view = view;
        this.configuredSources = configuredSources;
        this.perSource = perSource;
        this.maxTotal = maxTotal;
        this.concurrency = concurrency;
        this.timeoutSeconds = timeoutSeconds;
    }

    public ResponseEntity<CommonResult<Object>> create(String userId, String route, String key,
                                                        Map<String, Object> body) {
        ResponseEntity<CommonResult<Object>> replay = tasks.replay(userId, "POST", route, key, body);
        if (replay != null) {
            return replay;
        }
        Map<String, Object> profile = profiles.get(userId);
        long actual = ((Number) profile.get("profileVersion")).longValue();
        Integer expected = Data.integer(body, "expectedProfileVersion", null, 1, Integer.MAX_VALUE);
        if (expected == null) {
            throw BusinessException.validation("expectedProfileVersion", "expectedProfileVersion 不能为空");
        }
        if (expected.longValue() != actual) {
            throw BusinessException.versionConflict(actual);
        }
        Object activeResume = profile.get("activeResumeVersionId");
        if (activeResume == null) {
            throw BusinessException.conflict("请先确认简历并完整保存求职偏好", "INVALID_STATE");
        }
        String resumeVersionId = String.valueOf(activeResume);
        Map<String, Object> resume = resumes.version(resumeVersionId, userId);
        if (resume == null || !"CONFIRMED".equals(String.valueOf(resume.get("status")))) {
            throw BusinessException.conflict("当前生效简历未确认", "INVALID_STATE");
        }
        String targetRole = profile.get("targetRole") == null ? null : String.valueOf(profile.get("targetRole"));
        @SuppressWarnings("unchecked")
        List<String> profileCities = profile.get("cities") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        if (targetRole == null || profile.get("candidateType") == null || profileCities.isEmpty()) {
            throw BusinessException.conflict("请先完整保存目标岗位、求职类型和城市偏好", "INVALID_STATE");
        }

        Map<String, Object> filters = filters(body, profile, profileCities);
        List<String> sources = sources(body);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("profile", agentProfile(profile));
        payload.put("filters", filters);
        payload.put("sources", sources);
        payload.put("skillCatalog", profiles.agentSkillCatalog());
        payload.put("limits", Map.of("perSource", perSource, "maxTotal", maxTotal,
                "concurrency", concurrency, "sourceTimeoutSeconds", timeoutSeconds));

        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("profileVersion", actual);
        versions.put("resumeVersionId", resumeVersionId);
        String searchId = UUID.randomUUID().toString();
        Map<String, Object> batchSnapshot = new LinkedHashMap<>();
        batchSnapshot.put("includeUnknown", filters.get("includeUnknownSize"));
        batchSnapshot.put("filters", filters);
        batchSnapshot.put("jobs", List.of());
        return tasks.create(userId, "POST", route, key, body, "JOB_SEARCH", "JOB_SEARCH", searchId,
                versions, payload, taskId -> {
                    jobs.insertBatch(searchId, userId, taskId, actual, resumeVersionId, json.stringify(batchSnapshot));
                    mapper.insertSearch(searchId, userId, taskId, searchId, actual, resumeVersionId,
                            json.stringify(filters));
                });
    }

    public Map<String, Object> detail(String userId, String searchId) {
        Map<String, Object> search = mapper.search(searchId, userId);
        if (search == null) {
            throw BusinessException.notFound();
        }
        Map<String, Object> batch = jobs.batch(searchId, userId);
        return vo(search, batch);
    }

    public PageResult<Map<String, Object>> items(String userId, String searchId, Integer current, Integer size,
                                                  String keyword, String cityCode, String candidateType,
                                                  String companySizes, Boolean includeUnknownSize) {
        if (mapper.search(searchId, userId) == null) {
            throw BusinessException.notFound();
        }
        int page = Data.page(current);
        int pageSize = Data.size(size);
        String text = keyword == null || keyword.isBlank() ? null : keyword.trim();
        if (text != null && text.length() > 100) {
            throw BusinessException.validation("keyword", "关键词不能超过 100 字符");
        }
        String type = candidateType == null || candidateType.isBlank() ? null : candidateType.trim();
        if (type != null && !CANDIDATE_TYPES.contains(type)) {
            throw BusinessException.validation("candidateType", "求职类型非法");
        }
        List<String> sizes = new ArrayList<>();
        if (companySizes != null && !companySizes.isBlank()) {
            for (String item : companySizes.split(",")) {
                String value = item.trim();
                if (value.isEmpty()) {
                    continue;
                }
                CompanySizes.validate(value);
                if (!sizes.contains(value)) {
                    sizes.add(value);
                }
            }
        }
        boolean includeUnknown = includeUnknownSize == null || includeUnknownSize;
        List<Map<String, Object>> records = mapper.searchItems(searchId, text,
                        cityCode == null || cityCode.isBlank() ? null : cityCode.trim(), type, sizes, includeUnknown,
                        pageSize, Data.offset(current, size))
                .stream().map(row -> itemVo(row, userId)).toList();
        long total = mapper.searchItemCount(searchId, text,
                cityCode == null || cityCode.isBlank() ? null : cityCode.trim(), type, sizes, includeUnknown);
        return new PageResult<>(total, records, page, pageSize);
    }

    public PageResult<Map<String, Object>> list(String userId, Integer current, Integer size) {
        int page = Data.page(current);
        int pageSize = Data.size(size);
        List<Map<String, Object>> records = mapper.page(userId, pageSize, Data.offset(current, size)).stream()
                .map(row -> vo(row, jobs.batch(String.valueOf(row.get("id")), userId))).toList();
        return new PageResult<>(mapper.count(userId), records, page, pageSize);
    }

    public List<Map<String, Object>> matchItemOptions(String userId) {
        long version = ((Number) profiles.get(userId).get("profileVersion")).longValue();
        return mapper.matchItemOptions(userId, version).stream().map(row -> {
            Map<String, Object> snapshot = json.map(String.valueOf(row.get("source_snapshot_json")));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("matchItemId", row.get("id"));
            item.put("batchId", row.get("batch_id"));
            item.put("jobId", row.get("job_id"));
            item.put("jobVersion", row.get("job_version"));
            item.put("title", first(row.get("title"), snapshot.get("title")));
            item.put("companyName", first(row.get("company_name"), snapshot.get("companyName")));
            item.put("cityCode", first(row.get("city_code"), snapshot.get("cityCode")));
            item.put("matchScore", row.get("match_score"));
            item.put("eligibilityStatus", row.get("eligibility_status"));
            item.put("reasons", json.convert(json.parse(String.valueOf(row.get("reasons_json"))), Object.class));
            item.put("sourceName", sourceField(snapshot, "name"));
            item.put("sourceUrl", snapshot.get("sourceUrl"));
            item.put("verification", snapshot.get("verification"));
            item.put("createdAt", view.value(row.get("created_at")));
            return item;
        }).toList();
    }

    private Map<String, Object> vo(Map<String, Object> search, Map<String, Object> batch) {
        if (batch == null) {
            batch = Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", search.get("id"));
        result.put("taskId", search.get("task_id"));
        result.put("status", search.get("status"));
        result.put("profileVersion", search.get("profile_version"));
        result.put("resumeVersionId", search.get("resume_version_id"));
        result.put("filters", json.map(String.valueOf(search.get("filters_json"))));
        result.put("queryPlan", search.get("query_plan_json") == null ? null
                : json.map(String.valueOf(search.get("query_plan_json"))));
        result.put("sources", search.get("sources_json") == null ? List.of()
                : json.convert(json.parse(String.valueOf(search.get("sources_json"))), Object.class));
        result.put("summary", search.get("summary_json") == null ? null
                : json.map(String.valueOf(search.get("summary_json"))));
        boolean profileStale = ((Number) search.get("profile_version")).longValue()
                != ((Number) profiles.get(String.valueOf(search.get("user_id"))).get("profileVersion")).longValue();
        boolean jobStale = "READY".equals(String.valueOf(search.get("status")))
                && jobs.staleItems(String.valueOf(search.get("id"))) > 0;
        boolean expired = search.get("created_at") instanceof LocalDateTime created
                && created.isBefore(LocalDateTime.now(ZoneOffset.UTC).minusDays(90));
        result.put("isStale", profileStale || jobStale || expired);
        result.put("staleReason", profileStale ? "PROFILE_CHANGED" : jobStale ? "JOB_CHANGED"
                : expired ? "SEARCH_EXPIRED" : null);
        result.put("createdAt", view.value(search.get("created_at")));
        result.put("updatedAt", view.value(search.get("updated_at")));
        return result;
    }

    private Map<String, Object> itemVo(Map<String, Object> row, String userId) {
        Map<String, Object> snapshot = json.map(String.valueOf(row.get("source_snapshot_json")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("batchId", row.get("batch_id"));
        result.put("jobId", row.get("job_id"));
        result.put("jobVersion", row.get("job_version"));
        result.put("rankNo", row.get("rank_no"));
        result.put("title", first(row.get("job_title"), snapshot.get("title")));
        result.put("companyName", first(row.get("company_name"), snapshot.get("companyName")));
        result.put("cityCode", first(row.get("job_city_code"), snapshot.get("cityCode")));
        result.put("candidateType", first(row.get("job_candidate_type"), snapshot.get("candidateType")));
        result.put("companySize", CompanySizes.normalize(
                row.get("job_company_size") == null ? null : String.valueOf(row.get("job_company_size"))));
        result.put("publishedAt", view.value(row.get("published_at")));
        result.put("updatedAt", snapshot.get("updatedAt"));
        result.put("fetchedAt", view.value(row.get("fetched_at")));
        result.put("recruitmentStatus", snapshot.get("recruitmentStatus"));
        result.put("recruitmentCheckedAt", snapshot.get("recruitmentCheckedAt"));
        result.put("recruitmentEvidence", snapshot.get("recruitmentEvidence"));
        result.put("verificationStatus", snapshot.get("verificationStatus"));
        result.put("verificationReason", snapshot.get("verificationReason"));
        result.put("verificationEvidence", snapshot.get("verificationEvidence"));
        result.put("verificationCheckedAt", snapshot.get("verificationCheckedAt"));
        result.put("verificationHttpStatus", snapshot.get("verificationHttpStatus"));
        result.put("minMonthlySalaryCents", first(row.get("min_monthly_salary_cents"), snapshot.get("salaryMinMonthlyCents")));
        result.put("maxMonthlySalaryCents", first(row.get("max_monthly_salary_cents"), snapshot.get("salaryMaxMonthlyCents")));
        result.put("salaryText", snapshot.get("salaryText"));
        result.put("companySizeText", snapshot.get("companySizeText"));
        result.put("fieldSources", snapshot.get("fieldSources"));
        result.put("experienceText", snapshot.get("experienceText"));
        result.put("experienceMonthsMin", snapshot.get("experienceMonthsMin"));
        result.put("educationText", snapshot.get("educationText"));
        result.put("educationMin", snapshot.get("educationMin"));
        result.put("jdSummary", row.get("jd_summary"));
        result.put("source", snapshot.get("source"));
        result.put("sourceUrl", snapshot.get("sourceUrl"));
        result.put("verifiedAt", snapshot.get("verifiedAt"));
        result.put("verification", snapshot.get("verification"));
        result.put("warnings", snapshot.get("warnings"));
        result.put("aliases", snapshot.get("aliases"));
        result.put("fingerprint", snapshot.get("fingerprint"));
        result.put("matchScore", row.get("match_score"));
        result.put("scoreVersion", "job-search-match-v1");
        result.put("eligibility", json.convert(json.parse(String.valueOf(row.get("eligibility_json"))), Object.class));
        result.put("components", json.convert(json.parse(String.valueOf(row.get("components_json"))), Object.class));
        result.put("gaps", json.convert(json.parse(String.valueOf(row.get("gaps_json"))), Object.class));
        result.put("reasons", json.convert(json.parse(String.valueOf(row.get("reasons_json"))), Object.class));
        result.put("isFavorite", jobs.isFavorite(userId, String.valueOf(row.get("job_id"))) > 0);
        return result;
    }

    private Map<String, Object> filters(Map<String, Object> body, Map<String, Object> profile,
                                        List<String> profileCities) {
        String keyword = Data.text(body, "keyword", false, 100);
        List<String> cities = strings(body, "cities", 20, profileCities);
        for (String code : cities) {
            if (!code.matches("\\d{6}")) {
                throw BusinessException.validation("cities", "城市编码必须是 6 位行政区代码");
            }
        }
        List<String> types = strings(body, "candidateTypes", 3, List.of());
        for (String type : types) {
            if (!CANDIDATE_TYPES.contains(type)) {
                throw BusinessException.validation("candidateTypes", "求职类型非法: " + type);
            }
        }
        List<String> sizes = strings(body, "companySizes", 7, List.of());
        for (String size : sizes) {
            CompanySizes.validate(size);
        }
        boolean includeUnknown = Data.bool(body, "includeUnknownSize", true);
        int maxResults = Data.integer(body, "maxResults", 30, 1, 60);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyword", keyword == null || keyword.isBlank() ? null : keyword);
        result.put("cities", cities);
        result.put("candidateTypes", types);
        result.put("companySizes", sizes);
        result.put("includeUnknownSize", includeUnknown);
        result.put("maxResults", maxResults);
        return result;
    }

    private List<String> strings(Map<String, Object> body, String field, int max, List<String> fallback) {
        List<Object> raw = Data.list(body, field, false, max);
        List<String> result = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof String value) || value.isBlank() || value.length() > 64) {
                throw BusinessException.validation(field, field + " 包含非法值");
            }
            String text = value.trim();
            if (!result.contains(text)) {
                result.add(text);
            }
        }
        return result.isEmpty() ? new ArrayList<>(fallback) : result;
    }

    private List<String> sources(Map<String, Object> body) {
        List<String> configured = new ArrayList<>();
        for (String item : configuredSources.split(",")) {
            String key = item.trim();
            if (!key.isEmpty() && SOURCE_KEYS.contains(key) && !configured.contains(key)) {
                configured.add(key);
            }
        }
        if (configured.isEmpty()) {
            configured.addAll(List.of("company_official", "web", "baidu", "tencent", "greenhouse"));
        }
        if (configured.contains("web") && !configured.contains("company_official")) configured.add(0, "company_official");
        List<Object> raw = Data.list(body, "sources", false, 10);
        if (raw.isEmpty()) {
            return configured;
        }
        List<String> requested = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof String value) || !SOURCE_KEYS.contains(value)) {
                throw BusinessException.validation("sources", "来源不受支持");
            }
            if (configured.contains(value) && !requested.contains(value)) {
                requested.add(value);
            }
        }
        if (requested.isEmpty()) {
            throw BusinessException.validation("sources", "至少选择一个已启用来源");
        }
        return requested;
    }

    private Map<String, Object> agentProfile(Map<String, Object> profile) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("userId", "profileVersion", "targetRole", "candidateType", "cities",
                "minMonthlySalaryCents", "maxMonthlySalaryCents", "activeResumeVersionId", "updatedAt")) {
            result.put(field, profile.get(field));
        }
        Object rawFacts = profile.get("facts");
        Map<String, Object> facts = new LinkedHashMap<>();
        if (rawFacts instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                facts.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        if (!(facts.get("warnings") instanceof List<?>)) {
            facts.put("warnings", List.of());
        }
        result.put("facts", facts);
        Object warnings = profile.get("warnings");
        result.put("warnings", warnings instanceof List<?> ? warnings : List.of());
        return result;
    }

    private Object first(Object primary, Object fallback) {
        return primary == null ? fallback : primary;
    }

    private Object sourceField(Map<String, Object> snapshot, String field) {
        Object source = snapshot.get("source");
        if (source instanceof Map<?, ?> map) {
            return map.get(field);
        }
        return null;
    }

    @SuppressWarnings("unused")
    private Map<String, Map<String, Object>> index(List<Map<String, Object>> rows, Function<Map<String, Object>, String> key) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            result.put(key.apply(row), row);
        }
        return result;
    }
}
