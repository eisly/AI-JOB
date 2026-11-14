package com.aijob.backend.job;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.aijob.backend.ability.AbilityService;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.task.AiTask;
import com.aijob.backend.task.TaskResultHandler;

import tools.jackson.databind.JsonNode;

@Component
public class JobSearchResultHandler implements TaskResultHandler {

    private static final Set<String> CANDIDATE_TYPES = Set.of("INTERNSHIP", "CAMPUS", "EXPERIENCED");
    private static final Set<String> EDUCATION = Set.of("HIGH_SCHOOL", "ASSOCIATE", "BACHELOR", "MASTER", "DOCTOR");
    private static final Pattern EXPLICIT_DEGREE = Pattern.compile("(高中|大专|本科|硕士|博士)\\s*(?:及|或)?以上");
    private static final Pattern GRADUATING_CLASS = Pattern.compile(
            "(20\\d{2})\\s*届(?:\\s*(或|至|和|、|[-~～])\\s*(20\\d{2})\\s*届)?\\s*(?:应届)?毕业生");
    private static final Pattern EXPLICIT_EXPERIENCE = Pattern.compile(
            "(?:至少|拥有|具有|具备|要求)\\s*(\\d{1,2})\\s*年\\s*(?:及)?以上[^。；;]{0,24}(?:经验|经历)");
    private static final Set<String> SOURCE_TYPES = Set.of("OFFICIAL", "AUTHORIZED", "PUBLIC_SEARCH", "USER_INPUT");
    private static final Set<String> VERIFICATION_STATUSES = Set.of(
            "ACTIVE_CONFIRMED", "ACTIVE_PROBABLE", "UNKNOWN", "CLOSED");
    private static final Set<String> ACTIVE_EVIDENCE = Set.of(
            "OFFICIAL_LIST", "DETAIL_APPLY_LINK", "DETAIL_TEXT");
    private static final Set<String> CLOSED_EVIDENCE = Set.of("OFFICIAL_CLOSED", "EXPIRED");
    private static final Duration CONFIRMED_FRESHNESS = Duration.ofHours(6);
    private static final Duration PROBABLE_FRESHNESS = Duration.ofHours(24);
    private static final Set<String> QUOTE_KEYS = Set.of("educationMin", "graduationYearMin",
            "graduationYearMax", "experienceMonthsMin");
    private static final Map<String, String> OFFSITE_NAMES = Map.of(
            "tencent", "腾讯招聘官网",
            "baidu", "百度招聘官网",
            "greenhouse", "公司招聘官网（Greenhouse ATS）",
            "liepin", "猎聘",
            "boss", "BOSS直聘",
            "zhaopin", "智联招聘");

    private final JobSearchMapper mapper;
    private final JobMapper jobs;
    private final JobService jobService;
    private final JsonService json;
    private final EligibilityService eligibility;
    private final MatchScoreService scores;
    private final AbilityService ability;
    private final ProfileService profiles;

    public JobSearchResultHandler(JobSearchMapper mapper, JobMapper jobs, JobService jobService, JsonService json,
                                  EligibilityService eligibility, MatchScoreService scores, AbilityService ability,
                                  ProfileService profiles) {
        this.mapper = mapper;
        this.jobs = jobs;
        this.jobService = jobService;
        this.json = json;
        this.eligibility = eligibility;
        this.scores = scores;
        this.ability = ability;
        this.profiles = profiles;
    }

    @Override
    public Set<String> operations() {
        return Set.of("JOB_SEARCH");
    }

    @Override
    public int success(AiTask task, JsonNode result) {
        return search(task, result);
    }

    @Override
    public void failure(AiTask task, JsonNode error) {
        mapper.updateStatus(task.getResourceId(), "FAILED");
        Map<String, Object> batch = jobs.batch(task.getResourceId(), task.getUserId());
        if (batch != null) {
            jobs.updateBatch(task.getResourceId(), "FAILED", task.getId());
        }
    }

    @Override
    public void prepareRetry(AiTask original, String newTaskId) {
        mapper.prepareRetry(original.getResourceId(), newTaskId);
        jobs.updateBatch(original.getResourceId(), "GENERATING", newTaskId);
    }

    /**
     * S03/A05：搜索业务结果摘要。任务执行成功不等于业务有结果——
     * 来源全部失败且无结果、部分来源失败但有结果、正常空结果、正常有结果
     * 需要在任务列表可区分。依据落库的 summary（warnings + uniqueCount）判定 outcome。
     */
    @Override
    public Map<String, Object> resultSummary(AiTask task) {
        Map<String, Object> search = mapper.search(task.getResourceId(), task.getUserId());
        if (search == null || search.get("summary_json") == null) {
            return Map.of();
        }
        Map<String, Object> summary = json.map(String.valueOf(search.get("summary_json")));
        List<String> warnings = strings(summary.get("warnings"), 20, 64);
        int uniqueCount = summary.get("uniqueCount") instanceof Number number ? number.intValue() : 0;
        boolean sourceFailed = warnings.contains("SEARCH_FAILED");
        boolean sourcePartial = warnings.contains("SOURCE_PARTIAL");
        String outcome;
        if (uniqueCount > 0) {
            outcome = (sourceFailed || sourcePartial) ? "PARTIAL" : "OK";
        } else {
            outcome = sourceFailed ? "NO_RESULTS_SOURCE_FAILED" : "EMPTY";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", outcome);
        result.put("resultCount", uniqueCount);
        return result;
    }

    @SuppressWarnings("unchecked")
    private int search(AiTask task, JsonNode result) {
        Map<String, Object> batch = jobs.lockBatch(task.getResourceId(), task.getUserId());
        if (batch == null || !"GENERATING".equals(String.valueOf(batch.get("status")))) {
            throw BusinessException.conflict("搜索任务状态已变化", "INVALID_STATE");
        }
        Map<String, Object> payload = json.map(task.getInputSnapshotJson());
        Map<String, Object> profile = (Map<String, Object>) payload.get("profile");
        Map<String, Object> facts = (Map<String, Object>) profile.getOrDefault("facts", Map.of());
        Map<String, Object> filters = (Map<String, Object>) payload.getOrDefault("filters", Map.of());
        List<String> cities = strings(filters.get("cities"), 20, 6);
        List<String> types = strings(filters.get("candidateTypes"), 3, 20);
        List<String> sizes = strings(filters.get("companySizes"), 7, 20);
        boolean includeUnknownSize = !Boolean.FALSE.equals(filters.get("includeUnknownSize"));
        Integer requestedCount = integer(filters.get("maxResults"));
        int resultLimit = requestedCount == null ? 30 : Math.clamp(requestedCount, 1, 60);
        Map<String, Object> root = json.map(result);
        List<Map<String, Object>> rawItems = root.get("items") instanceof List<?> list
                ? (List<Map<String, Object>>) list : null;
        if (rawItems == null) {
            throw BusinessException.aiSchema("搜索结果缺少 items");
        }
        Set<String> catalog = profiles.agentSkillCatalog().stream()
                .map(item -> String.valueOf(item.get("code"))).collect(Collectors.toSet());
        Map<String, Map<String, Object>> snapshots = ability.snapshots(task.getUserId()).stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("skill_code", row.get("skill_code"));
            item.put("score", row.get("score"));
            item.put("sample_count", row.get("sample_count"));
            return item;
        }).collect(Collectors.toMap(item -> String.valueOf(item.get("skill_code")), Function.identity()));

        Map<String, SearchCandidate> unique = new LinkedHashMap<>();
        Map<String, Integer> bySource = new LinkedHashMap<>();
        Map<String, Integer> providers = new LinkedHashMap<>();
        int dropped = 0;
        Map<String, Integer> rejected = new LinkedHashMap<>();
        int valid = 0;
        String fallbackType = fallbackCandidateType(types, profile);
        for (Map<String, Object> raw : rawItems) {
            SearchCandidate candidate = normalize(raw, catalog, fallbackType);
            String reject = candidate == null ? "INVALID_FIELDS" :
                    (!cities.isEmpty() && candidate.cityCode != null && !cities.contains(candidate.cityCode)) ? "CITY" :
                    (!types.isEmpty() && !candidate.candidateTypeInferred && !types.contains(candidate.candidateType)) ? "TYPE" :
                    !matchesSize(candidate.companySize, sizes, includeUnknownSize) ? "SIZE" : null;
            if (reject != null) {
                dropped++;
                rejected.merge(reject, 1, Integer::sum);
                continue;
            }
            valid++;
            bySource.merge(candidate.sourceKey, 1, Integer::sum);
            for (String provider : candidate.searchProviders) {
                providers.merge(provider, 1, Integer::sum);
            }
            SearchCandidate existing = unique.get(candidate.sourceUrl);
            if (existing == null) {
                unique.put(candidate.sourceUrl, candidate);
            } else {
                existing.merge(candidate);
            }
        }
        List<SearchCandidate> candidates = new ArrayList<>(unique.values());
        Map<String, Integer> qualification = new LinkedHashMap<>(Map.of("PASS", 0, "UNKNOWN", 0, "FAIL", 0));
        for (SearchCandidate candidate : candidates) {
            prepare(candidate, profile, facts, snapshots);
            qualification.merge(candidate.eligibilityStatus, 1, Integer::sum);
        }
        candidates.sort(Comparator
                .comparingInt((SearchCandidate item) -> "CLOSED".equals(item.verificationStatus) ? 3
                        : switch (item.eligibilityStatus) {
                            case "PASS" -> 0;
                            case "UNKNOWN" -> 1;
                            default -> 2;
                        })
                .thenComparing(item -> item.warnings.contains("STALE_POSTING"))
                .thenComparing(item -> item.matchScore, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparingInt(item -> -verificationRank(item.verificationStatus))
                .thenComparingInt(item -> -completeness(item))
                .thenComparingInt(item -> SearchCandidate.priority(item.sourceType))
                .thenComparing(item -> item.publishedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(item -> item.fingerprint));

        int uniqueCount = candidates.size();
        candidates = candidates.subList(0, Math.min(resultLimit, uniqueCount));
        int rank = 1;
        int multiSource = 0;
        int unknownSize = 0;
        int possiblyExpired = 0;
        int confirmed = 0;
        int probable = 0;
        int unknownVerification = 0;
        int closedCount = 0;
        for (SearchCandidate candidate : candidates) {
            if (candidate.aliases.size() > 1) {
                multiSource++;
            }
            if (CompanySizes.SIZE_UNKNOWN.equals(candidate.companySize)) {
                unknownSize++;
            }
            switch (candidate.verificationStatus) {
                case "ACTIVE_CONFIRMED" -> confirmed++;
                case "ACTIVE_PROBABLE" -> probable++;
                case "CLOSED" -> closedCount++;
                default -> unknownVerification++;
            }
            if (candidate.warnings.contains("CLOSED_JOB")) {
                possiblyExpired++;
            }
            persist(task.getUserId(), task.getResourceId(), candidate, rank++);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rawCount", rawItems.size());
        summary.put("validCount", valid);
        summary.put("uniqueCount", candidates.size());
        summary.put("droppedCount", dropped);
        summary.put("rejectedByReason", rejected);
        summary.put("qualification", qualification);
        summary.put("uniqueBeforeLimit", uniqueCount);
        summary.put("truncatedCount", uniqueCount - candidates.size());
        summary.put("agentMetrics", root.get("metrics"));
        summary.put("agentDedupe", root.get("dedupe"));
        summary.put("mergedCount", valid - uniqueCount);
        summary.put("multiSourceCount", multiSource);
        summary.put("unknownSizeCount", unknownSize);
        summary.put("possiblyExpiredCount", possiblyExpired);
        summary.put("bySource", bySource);
        summary.put("searchProviders", providers);
        summary.put("verification", Map.of(
                "ACTIVE_CONFIRMED", confirmed,
                "ACTIVE_PROBABLE", probable,
                "UNKNOWN", unknownVerification,
                "CLOSED", closedCount));
        summary.put("warnings", root.get("warnings") == null ? List.of() : root.get("warnings"));
        mapper.complete(task.getResourceId(), "READY", json.stringify(root.get("queryPlan")),
                json.stringify(root.get("sources")), json.stringify(summary));
        jobs.updateBatch(task.getResourceId(), "READY", task.getId());
        return 1;
    }

    private String fallbackCandidateType(List<String> types, Map<String, Object> profile) {
        if (types.size() == 1) {
            return types.get(0);
        }
        String profileType = text(profile.get("candidateType"));
        return profileType != null && CANDIDATE_TYPES.contains(profileType) ? profileType : "EXPERIENCED";
    }

    private String resolveVerificationStatus(Map<String, Object> raw, String sourceKey, String evidence,
                                              LocalDateTime checkedAt, LocalDateTime now) {
        String status = text(raw.get("verificationStatus"));
        if (status == null || !VERIFICATION_STATUSES.contains(status)) {
            status = legacyVerificationStatus(raw, sourceKey, evidence, checkedAt, now);
        }
        if ("ACTIVE_CONFIRMED".equals(status)
                && !(activeEvidenceAllowed(sourceKey, evidence) && fresh(checkedAt, CONFIRMED_FRESHNESS, now))) {
            return "UNKNOWN";
        }
        if ("ACTIVE_PROBABLE".equals(status)
                && !(evidence != null && ACTIVE_EVIDENCE.contains(evidence) && fresh(checkedAt, PROBABLE_FRESHNESS, now))) {
            return "UNKNOWN";
        }
        return status;
    }

    private String legacyVerificationStatus(Map<String, Object> raw, String sourceKey, String evidence,
                                             LocalDateTime checkedAt, LocalDateTime now) {
        if ("CLOSED".equals(raw.get("recruitmentStatus")) || (evidence != null && CLOSED_EVIDENCE.contains(evidence))) {
            return "CLOSED";
        }
        boolean confirmed = "OPEN".equals(raw.get("recruitmentStatus"))
                && activeEvidenceAllowed(sourceKey, evidence) && fresh(checkedAt, CONFIRMED_FRESHNESS, now);
        if (confirmed) {
            return "ACTIVE_CONFIRMED";
        }
        boolean probable = "PROBABLE".equals(raw.get("recruitmentStatus"))
                && evidence != null && ACTIVE_EVIDENCE.contains(evidence) && fresh(checkedAt, PROBABLE_FRESHNESS, now);
        return probable ? "ACTIVE_PROBABLE" : "UNKNOWN";
    }

    private boolean activeEvidenceAllowed(String sourceKey, String evidence) {
        if (evidence == null) {
            return false;
        }
        if (Set.of("baidu", "tencent", "greenhouse", "lever").contains(sourceKey)) {
            return "OFFICIAL_LIST".equals(evidence);
        }
        return Set.of("web", "company_official").contains(sourceKey) && Set.of("OFFICIAL_LIST", "DETAIL_APPLY_LINK", "DETAIL_TEXT").contains(evidence);
    }

    private boolean fresh(LocalDateTime checkedAt, Duration window, LocalDateTime now) {
        return checkedAt != null && !checkedAt.isAfter(now.plusMinutes(1))
                && !checkedAt.isBefore(now.minus(window));
    }

    private String defaultReason(String status, String evidence) {
        return switch (status) {
            case "ACTIVE_CONFIRMED", "ACTIVE_PROBABLE" -> "ACTIVE_EVIDENCE_FOUND";
            case "CLOSED" -> CLOSED_EVIDENCE.contains(evidence) ? evidence : "OFFICIAL_CLOSED";
            default -> "INSUFFICIENT_EVIDENCE";
        };
    }

    private String legacyStatus(String verificationStatus) {
        return switch (verificationStatus) {
            case "ACTIVE_CONFIRMED" -> "OPEN";
            case "ACTIVE_PROBABLE" -> "PROBABLE";
            case "CLOSED" -> "CLOSED";
            default -> "UNKNOWN";
        };
    }

    private static int verificationRank(String status) {
        return switch (status == null ? "" : status) {
            case "ACTIVE_CONFIRMED" -> 4;
            case "ACTIVE_PROBABLE" -> 3;
            case "CLOSED" -> 1;
            default -> 2;
        };
    }

    private int completeness(SearchCandidate candidate) {
        int score = 0;
        if (candidate.cityCode != null) score++;
        if (candidate.salaryMin != null && candidate.salaryMax != null) score++;
        if (candidate.experienceText != null || candidate.experienceMonths != null) score++;
        if (candidate.educationMin != null) score++;
        if (candidate.publishedAt != null) score++;
        if (!candidate.requirements.getOrDefault("skills", List.of()).equals(List.of())) score++;
        return score;
    }

    private boolean matchesSize(String size, List<String> sizes, boolean includeUnknown) {
        if (CompanySizes.SIZE_UNKNOWN.equals(size)) {
            return includeUnknown || sizes.contains(CompanySizes.SIZE_UNKNOWN);
        }
        return sizes.isEmpty() || sizes.contains(size);
    }

    private SearchCandidate normalize(Map<String, Object> raw, Set<String> catalog, String fallbackType) {
        String sourceKey = text(raw.get("sourceKey"));
        String sourceName = text(raw.get("sourceName"));
        String sourceUrl = text(raw.get("sourceUrl"));
        String title = text(raw.get("title"));
        String company = text(raw.get("companyName"));
        String jdText = text(raw.get("jdText"));
        if (sourceKey == null || title == null || company == null || jdText == null
                || !httpUrl(sourceUrl) || !sourceUrl.startsWith("https://")) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String evidence = firstText(raw.get("verificationEvidence"), raw.get("recruitmentEvidence"));
        String reason = text(raw.get("verificationReason"));
        LocalDateTime checkedAt = time(raw.get("verificationCheckedAt"));
        if (checkedAt == null) {
            checkedAt = time(raw.get("recruitmentCheckedAt"));
        }
        Integer verificationHttp = integer(raw.get("verificationHttpStatus"));
        String verificationStatus = resolveVerificationStatus(raw, sourceKey, evidence, checkedAt, now);
        if ("UNKNOWN".equals(verificationStatus) && "ACTIVE_EVIDENCE_FOUND".equals(reason)) {
            reason = "INSUFFICIENT_EVIDENCE";
        }
        boolean confirmedOpen = "ACTIVE_CONFIRMED".equals(verificationStatus);
        if (sourceName == null) {
            sourceName = OFFSITE_NAMES.getOrDefault(sourceKey, sourceKey);
        }
        String sourceType = text(raw.get("sourceType"));
        if (sourceType == null || !SOURCE_TYPES.contains(sourceType)) {
            sourceType = "PUBLIC_SEARCH";
        }
        String verification = "VERIFIED".equals(text(raw.get("verification"))) ? "VERIFIED" : "UNVERIFIED";
        SourceDefaults defaults = sourceDefaults(sourceKey, sourceType, verification);
        sourceType = defaults.type();
        verification = defaults.verification();

        SearchCandidate candidate = new SearchCandidate();
        candidate.sourceKey = sourceKey;
        candidate.sourceName = sourceName;
        candidate.sourceType = sourceType;
        candidate.verification = verification;
        candidate.sourceUrl = sourceUrl;
        candidate.verificationStatus = verificationStatus;
        candidate.verificationReason = reason == null ? defaultReason(verificationStatus, evidence) : reason;
        candidate.verificationEvidence = evidence;
        candidate.verificationCheckedAt = checkedAt;
        candidate.verificationHttpStatus = verificationHttp;
        candidate.verificationHistory = maps(raw.get("verificationHistory"), 10);
        candidate.searchProviders = strings(raw.get("searchProviders"), 5, 32);
        candidate.recruitmentStatus = legacyStatus(verificationStatus);
        candidate.recruitmentEvidence = "UNKNOWN".equals(verificationStatus) ? null : evidence;
        candidate.recruitmentCheckedAt = confirmedOpen || "ACTIVE_PROBABLE".equals(verificationStatus) ? checkedAt : null;
        candidate.title = limit(title, 100, candidate.warnings, "TITLE");
        candidate.companyName = limit(company, 100, candidate.warnings, "COMPANY");
        candidate.cityCode = cityCode(text(raw.get("cityCode")));
        candidate.cityName = limitNullable(text(raw.get("cityName")), 100);
        if (candidate.cityCode == null) {
            candidate.warnings.add("CITY_UNKNOWN");
        }
        String rawCandidateType = text(raw.get("candidateType"));
        candidate.candidateTypeInferred = rawCandidateType == null || !CANDIDATE_TYPES.contains(rawCandidateType);
        candidate.candidateType = candidate.candidateTypeInferred ? fallbackType : rawCandidateType;
        if (candidate.candidateTypeInferred) {
            candidate.warnings.add("CANDIDATE_TYPE_UNKNOWN");
        }
        candidate.companySize = CompanySizes.normalize(text(raw.get("companySize")));
        candidate.salaryMin = integer(raw.get("salaryMinMonthlyCents"));
        candidate.salaryMax = integer(raw.get("salaryMaxMonthlyCents"));
        if (candidate.salaryMin != null && candidate.salaryMax != null && candidate.salaryMin > candidate.salaryMax) {
            candidate.salaryMin = null;
            candidate.salaryMax = null;
        }
        candidate.companySizeText = limitNullable(text(raw.get("companySizeText")), 200);
        if (raw.get("fieldSources") instanceof Map<?, ?> sources) {
            for (var entry : sources.entrySet()) {
                if (Set.of("salary", "companySize", "city", "education", "experience", "publishedAt", "updatedAt", "companyName").contains(String.valueOf(entry.getKey()))
                        && entry.getValue() instanceof Map<?, ?> value && httpUrl(text(value.get("sourceUrl")))) {
                    Map<String, Object> proof = new LinkedHashMap<>();
                    for (String key : List.of("sourceUrl", "sourceName", "sourceType", "rawText", "fetchedAt")) {
                        proof.put(key, limitNullable(text(value.get(key)), key.equals("sourceUrl") ? 2000 : 500));
                    }
                    candidate.fieldSources.put(String.valueOf(entry.getKey()), proof);
                }
            }
        }
        candidate.salaryText = limitNullable(text(raw.get("salaryText")), 200);
        candidate.experienceText = limitNullable(text(raw.get("experienceText")), 200);
        candidate.experienceMonths = integer(raw.get("experienceMonthsMin"));
        candidate.educationText = limitNullable(text(raw.get("educationText")), 200);
        String education = text(raw.get("educationMin"));
        candidate.educationMin = education != null && EDUCATION.contains(education) ? education : null;
        candidate.publishedAt = time(raw.get("publishedAt"));
        candidate.updatedAt = time(raw.get("updatedAt"));
        candidate.fetchedAt = time(raw.get("fetchedAt"));
        candidate.jdText = limit(jdText, 30000, candidate.warnings, "JD");
        candidate.requirements = requirements(raw.get("requirements"), candidate.jdText, catalog, candidate.warnings);
        candidate.semantic = decimal(raw.get("semanticScore"));
        if (candidate.semantic != null && (candidate.semantic.compareTo(BigDecimal.ZERO) < 0
                || candidate.semantic.compareTo(BigDecimal.ONE) > 0)) {
            candidate.semantic = null;
        }
        candidate.reasons = strings(raw.get("reasons"), 5, 500);
        candidate.gaps = gapSuggestions(raw.get("gaps"), candidate.requirements, 20);
        candidate.warnings.addAll(strings(raw.get("warnings"), 20, 120));
        candidate.aliases.addAll(aliases(raw.get("aliases")));
        candidate.aliases = dedupeAliases(candidate.aliases);
        if (candidate.aliases.isEmpty()) {
            candidate.aliases.add(alias(candidate));
        }
        annotate(candidate);
        candidate.fingerprint = fingerprint(candidate.companyName, candidate.title, candidate.cityCode);
        return candidate;
    }

    private SourceDefaults sourceDefaults(String key, String type, String verification) {
        if (Set.of("baidu", "tencent", "greenhouse", "lever", "company_official").contains(key)) {
            return new SourceDefaults("OFFICIAL", "VERIFIED");
        }
        if (Set.of("web", "liepin", "boss", "zhaopin").contains(key)) {
            return new SourceDefaults("PUBLIC_SEARCH", "UNVERIFIED");
        }
        return new SourceDefaults(type, verification);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requirements(Object raw, String jdText, Set<String> catalog, List<String> warnings) {
        Map<String, Object> source = raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        String education = text(source.get("educationMin"));
        if (education != null && !EDUCATION.contains(education)) {
            warnings.add("EDUCATION_DROPPED:INVALID");
            education = null;
        }
        Integer yearMin = integer(source.get("graduationYearMin"));
        Integer yearMax = integer(source.get("graduationYearMax"));
        if (yearMin != null && (yearMin < 1900 || yearMin > 2100)) {
            yearMin = null;
            warnings.add("GRADUATION_YEAR_DROPPED");
        }
        if (yearMax != null && (yearMax < 1900 || yearMax > 2100)) {
            yearMax = null;
            warnings.add("GRADUATION_YEAR_DROPPED");
        }
        if (yearMin != null && yearMax != null && yearMin > yearMax) {
            yearMin = null;
            yearMax = null;
            warnings.add("GRADUATION_YEAR_DROPPED");
        }
        Integer months = integer(source.get("experienceMonthsMin"));
        if (months != null && (months < 0 || months > 600)) {
            months = null;
            warnings.add("EXPERIENCE_DROPPED");
        }
        List<String> skills = new ArrayList<>();
        for (String code : strings(source.get("skills"), 50, 64)) {
            if (catalog.contains(code)) {
                if (!skills.contains(code)) {
                    skills.add(code);
                }
            } else {
                warnings.add("UNKNOWN_SKILL_DROPPED:" + code);
            }
        }
        Map<String, Object> levels = new LinkedHashMap<>();
        if (source.get("requiredSkillLevels") instanceof Map<?, ?> levelMap) {
            for (Map.Entry<?, ?> entry : levelMap.entrySet()) {
                String code = String.valueOf(entry.getKey());
                Integer level = integer(entry.getValue());
                if (skills.contains(code) && level != null && level >= 0 && level <= 100) {
                    levels.put(code, level);
                }
            }
        }
        Map<String, Object> quotes = new LinkedHashMap<>();
        if (source.get("quotes") instanceof Map<?, ?> quoteMap) {
            for (Map.Entry<?, ?> entry : quoteMap.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String quote = text(entry.getValue());
                if (QUOTE_KEYS.contains(key) && quote != null && jdText.contains(quote)) {
                    quotes.put(key, quote);
                }
            }
        }
        if (education != null && quotes.get("educationMin") instanceof String evidence) {
            var degree = EXPLICIT_DEGREE.matcher(evidence);
            if (degree.find()) {
                education = switch (degree.group(1)) {
                    case "高中" -> "HIGH_SCHOOL";
                    case "大专" -> "ASSOCIATE";
                    case "本科" -> "BACHELOR";
                    case "硕士" -> "MASTER";
                    default -> "DOCTOR";
                };
            }
        }
        if (yearMin == null && yearMax == null) {
            var graduation = GRADUATING_CLASS.matcher(jdText);
            if (graduation.find()) {
                String suffix = jdText.substring(graduation.end(), Math.min(jdText.length(), graduation.end() + 12));
                int first = Integer.parseInt(graduation.group(1));
                int second = graduation.group(3) == null ? first : Integer.parseInt(graduation.group(3));
                boolean continuous = second - first <= 1 || "至".equals(graduation.group(2))
                        || Set.of("-", "~", "～").contains(String.valueOf(graduation.group(2)));
                if (continuous && first <= second && !suffix.matches("^\\s*(?:优先|不限).*")) {
                    yearMin = first;
                    yearMax = second;
                    quotes.put("graduationYearMin", graduation.group());
                    quotes.put("graduationYearMax", graduation.group());
                }
            }
        }
        if (education != null && !quotes.containsKey("educationMin")) {
            education = null;
            warnings.add("REQUIREMENT_DROPPED_NO_QUOTE:educationMin");
        }
        if (months != null && !quotes.containsKey("experienceMonthsMin")) {
            var experience = EXPLICIT_EXPERIENCE.matcher(jdText);
            if (experience.find() && Integer.parseInt(experience.group(1)) * 12 == months) {
                quotes.put("experienceMonthsMin", experience.group());
            } else {
                months = null;
                warnings.add("REQUIREMENT_DROPPED_NO_QUOTE:experienceMonthsMin");
            }
        }
        if (yearMin != null && !quotes.containsKey("graduationYearMin")) {
            yearMin = null;
            warnings.add("REQUIREMENT_DROPPED_NO_QUOTE:graduationYearMin");
        }
        if (yearMax != null && !quotes.containsKey("graduationYearMax")) {
            yearMax = null;
            warnings.add("REQUIREMENT_DROPPED_NO_QUOTE:graduationYearMax");
        }
        result.put("educationMin", education);
        result.put("graduationYearMin", yearMin);
        result.put("graduationYearMax", yearMax);
        result.put("experienceMonthsMin", months);
        result.put("skills", skills);
        result.put("requiredSkillLevels", levels);
        result.put("quotes", quotes);
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> gapSuggestions(Object raw, Map<String, Object> requirements, int limit) {
        List<String> allowed = (List<String>) requirements.getOrDefault("skills", List.of());
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return result;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String code = text(map.get("skillCode"));
            String suggestion = text(map.get("suggestion"));
            if (code != null && allowed.contains(code) && suggestion != null) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("skillCode", code);
                value.put("suggestion", limit(suggestion, 500, new ArrayList<>(), "SUGGESTION"));
                result.add(value);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void prepare(SearchCandidate candidate, Map<String, Object> profile, Map<String, Object> facts,
                         Map<String, Map<String, Object>> snapshots) {
        candidate.roleCode = jobService.roleCode(candidate.title + " " + candidate.jdText);
        Map<String, Object> eligibilityResult = eligibility.evaluate(facts, candidate.requirements);
        if (!hasHardRequirement(candidate.requirements)) {
            List<Map<String, Object>> checks = new ArrayList<>(
                    (List<Map<String, Object>>) eligibilityResult.get("checks"));
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("field", "requirements");
            check.put("result", "UNKNOWN");
            check.put("actual", null);
            check.put("expected", null);
            check.put("reason", "JD 未提供可核验的明确硬性要求");
            check.put("sourceQuote", null);
            checks.add(check);
            eligibilityResult = new LinkedHashMap<>(eligibilityResult);
            eligibilityResult.put("status", "UNKNOWN");
            eligibilityResult.put("checks", checks);
        }
        candidate.eligibilityStatus = String.valueOf(eligibilityResult.get("status"));
        candidate.eligibility = eligibilityResult;
        List<String> confirmedSkills = facts.get("skills") instanceof List<?> list
                ? list.stream().filter(item -> item instanceof Map<?, ?>)
                    .map(item -> str(((Map<String, Object>) item).get("skillCode"))).toList()
                : List.of();
        @SuppressWarnings("unchecked")
        List<String> requiredSkills = (List<String>) candidate.requirements.getOrDefault("skills", List.of());
        BigDecimal skill = scores.skill(confirmedSkills, requiredSkills);
        BigDecimal preference = scores.preference(str(profile.get("targetRole")), candidate.roleCode,
                profile.get("cities") instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of(),
                candidate.cityCode);
        Integer total = scores.searchScore(skill, candidate.semantic, preference);
        candidate.matchScore = "FAIL".equals(candidate.eligibilityStatus) ? null : total;
        List<Map<String, Object>> components = new ArrayList<>();
        components.add(scores.component("skill", skill, BigDecimal.valueOf(0.60), "confirmed-resume-v1"));
        components.add(scores.component("semantic", candidate.semantic, BigDecimal.valueOf(0.25), "job-search-semantic-v1"));
        components.add(scores.component("preference", preference, BigDecimal.valueOf(0.15), "profile-preferences-v1"));
        candidate.components = components;
        candidate.gaps = buildGaps(requiredSkills, candidate.gaps, candidate.requirements, snapshots);
    }

    private boolean hasHardRequirement(Map<String, Object> requirements) {
        return requirements.get("educationMin") != null
                || requirements.get("experienceMonthsMin") != null
                || requirements.get("graduationYearMin") != null
                || requirements.get("graduationYearMax") != null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildGaps(List<String> requiredSkills, List<Map<String, Object>> suggestions,
                                                Map<String, Object> requirements,
                                                Map<String, Map<String, Object>> snapshots) {
        Map<String, String> suggestionByCode = new HashMap<>();
        for (Map<String, Object> item : suggestions) {
            suggestionByCode.put(String.valueOf(item.get("skillCode")), String.valueOf(item.get("suggestion")));
        }
        Map<String, Object> levels = (Map<String, Object>) requirements.getOrDefault("requiredSkillLevels", Map.of());
        List<Map<String, Object>> result = new ArrayList<>();
        for (String code : requiredSkills) {
            Map<String, Object> snapshot = snapshots.get(code);
            BigDecimal current = snapshot == null || snapshot.get("score") == null ? null : decimal(snapshot.get("score"));
            BigDecimal target = levels.get(code) instanceof Number number ? decimal(number) : null;
            BigDecimal gap = current == null || target == null ? null : target.subtract(current).max(BigDecimal.ZERO);
            int count = snapshot == null ? 0 : ((Number) snapshot.get("sample_count")).intValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("skillCode", code);
            item.put("currentScore", current);
            item.put("targetScore", target);
            item.put("gap", gap);
            item.put("evidenceStatus", count == 0 ? "MISSING" : count < 3 ? "LIMITED" : "SUFFICIENT");
            item.put("evidenceIds", List.of());
            item.put("suggestion", suggestionByCode.getOrDefault(code,
                    current == null ? "证据不足，建议完成有效评价" : "继续针对岗位要求练习"));
            result.add(item);
        }
        return result;
    }

    private void persist(String userId, String searchId, SearchCandidate candidate, int rank) {
        String sourceId = mapper.sourceId(candidate.sourceKey);
        String baseUrl = origin(candidate.sourceUrl);
        if (sourceId == null) {
            boolean web = "web".equals(candidate.sourceKey);
            mapper.upsertSource(UUID.randomUUID().toString(), candidate.sourceKey,
                    web ? "AI 全网搜索" : candidate.sourceName, candidate.sourceType,
                    web ? "https://api.deepseek.com" : baseUrl, candidate.verification);
            sourceId = mapper.sourceId(candidate.sourceKey);
        }
        if (sourceId == null) {
            throw BusinessException.conflict("岗位来源初始化失败", "INVALID_STATE");
        }
        String jobId = UUID.randomUUID().toString();
        boolean verified = "VERIFIED".equals(candidate.verification);
        mapper.insertJob(jobId, sourceId, userId, candidate.sourceUrl, verified);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("searchId", searchId);
        meta.put("sourceKey", candidate.sourceKey);
        meta.put("warnings", candidate.warnings);
        meta.put("aliases", candidate.aliases);
        meta.put("fingerprint", candidate.fingerprint);
        mapper.insertJobVersion(UUID.randomUUID().toString(), jobId, candidate.title, candidate.companyName,
                candidate.cityCode, candidate.roleCode, candidate.candidateType, candidate.companySize,
                candidate.publishedAt, candidate.fetchedAt, candidate.salaryMin, candidate.salaryMax,
                candidate.jdText, json.sha256(candidate.jdText), json.stringify(candidate.requirements),
                json.stringify(meta));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", candidate.title);
        snapshot.put("companyName", candidate.companyName);
        snapshot.put("cityCode", candidate.cityCode);
        snapshot.put("cityName", candidate.cityName);
        snapshot.put("candidateType", candidate.candidateType);
        snapshot.put("companySize", candidate.companySize);
        snapshot.put("publishedAt", iso(candidate.publishedAt));
        snapshot.put("updatedAt", iso(candidate.updatedAt));
        snapshot.put("fetchedAt", iso(candidate.fetchedAt));
        snapshot.put("recruitmentStatus", candidate.recruitmentStatus);
        snapshot.put("recruitmentCheckedAt", iso(candidate.recruitmentCheckedAt));
        snapshot.put("recruitmentEvidence", candidate.recruitmentEvidence);
        snapshot.put("verificationStatus", candidate.verificationStatus);
        snapshot.put("verificationReason", candidate.verificationReason);
        snapshot.put("verificationEvidence", candidate.verificationEvidence);
        snapshot.put("verificationCheckedAt", iso(candidate.verificationCheckedAt));
        snapshot.put("verificationHttpStatus", candidate.verificationHttpStatus);
        snapshot.put("verificationHistory", candidate.verificationHistory);
        snapshot.put("searchProviders", candidate.searchProviders);
        snapshot.put("salaryMinMonthlyCents", candidate.salaryMin);
        snapshot.put("salaryMaxMonthlyCents", candidate.salaryMax);
        snapshot.put("salaryText", candidate.salaryText);
        snapshot.put("companySizeText", candidate.companySizeText);
        snapshot.put("fieldSources", candidate.fieldSources);
        snapshot.put("experienceText", candidate.experienceText);
        snapshot.put("experienceMonthsMin", candidate.experienceMonths);
        snapshot.put("educationText", candidate.educationText);
        snapshot.put("educationMin", candidate.educationMin);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("id", sourceId);
        source.put("name", candidate.sourceName);
        source.put("type", candidate.sourceType);
        source.put("baseUrl", baseUrl);
        source.put("verificationStatus", candidate.verification);
        source.put("verifiedAt", verified ? iso(candidate.fetchedAt) : null);
        snapshot.put("source", source);
        snapshot.put("sourceUrl", candidate.sourceUrl);
        snapshot.put("verifiedAt", verified ? iso(candidate.fetchedAt) : null);
        snapshot.put("verification", candidate.verification);
        snapshot.put("warnings", candidate.warnings);
        snapshot.put("aliases", candidate.aliases);
        snapshot.put("fingerprint", candidate.fingerprint);
        jobs.insertItem(UUID.randomUUID().toString(), searchId, jobId, 1, rank,
                candidate.eligibilityStatus, json.stringify(candidate.eligibility), candidate.matchScore,
                json.stringify(candidate.components), json.stringify(candidate.gaps),
                json.stringify(candidate.reasons), json.stringify(snapshot));
    }

    private void annotate(SearchCandidate candidate) {
        if (candidate.publishedAt == null) {
            candidate.warnings.add("MISSING_PUBLISHED_AT");
        }
        if (candidate.salaryMin == null || candidate.salaryMax == null) {
            candidate.warnings.add("MISSING_SALARY");
        }
        if (candidate.educationMin == null) {
            candidate.warnings.add("MISSING_EDUCATION");
        }
        if (candidate.experienceText == null && candidate.experienceMonths == null) {
            candidate.warnings.add("MISSING_EXPERIENCE");
        }
        if (CompanySizes.SIZE_UNKNOWN.equals(candidate.companySize)) {
            candidate.warnings.add("COMPANY_SIZE_UNKNOWN");
        }
        if (!"VERIFIED".equals(candidate.verification)) {
            candidate.warnings.add("UNVERIFIED_SOURCE");
        }
        switch (candidate.verificationStatus) {
            case "ACTIVE_CONFIRMED" -> candidate.warnings.add("ACTIVE_CONFIRMED");
            case "ACTIVE_PROBABLE" -> candidate.warnings.add("ACTIVE_PROBABLE");
            case "CLOSED" -> candidate.warnings.add("CLOSED_JOB");
            default -> candidate.warnings.add("VERIFICATION_UNKNOWN");
        }
        candidate.warnings = dedupe(candidate.warnings, 20);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> aliases(Object raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return result;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String url = text(map.get("sourceUrl"));
            String sourceKey = text(map.get("sourceKey"));
            if (!httpUrl(url) || sourceKey == null) {
                continue;
            }
            Map<String, Object> alias = new LinkedHashMap<>();
            alias.put("sourceKey", sourceKey);
            alias.put("sourceName", text(map.get("sourceName")));
            alias.put("sourceType", text(map.get("sourceType")));
            alias.put("sourceUrl", url);
            alias.put("publishedAt", text(map.get("publishedAt")));
            alias.put("fetchedAt", text(map.get("fetchedAt")));
            alias.put("verification", "VERIFIED".equals(text(map.get("verification"))) ? "VERIFIED" : "UNVERIFIED");
            result.add(alias);
        }
        return result;
    }

    private static Map<String, Object> alias(SearchCandidate candidate) {
        Map<String, Object> alias = new LinkedHashMap<>();
        alias.put("sourceKey", candidate.sourceKey);
        alias.put("sourceName", candidate.sourceName);
        alias.put("sourceType", candidate.sourceType);
        alias.put("sourceUrl", candidate.sourceUrl);
        alias.put("publishedAt", iso(candidate.publishedAt));
        alias.put("fetchedAt", iso(candidate.fetchedAt));
        alias.put("verification", candidate.verification);
        return alias;
    }

    private static List<Map<String, Object>> dedupeAliases(List<Map<String, Object>> aliases) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> alias : aliases) {
            result.putIfAbsent(String.valueOf(alias.get("sourceKey")) + "|" + alias.get("sourceUrl"), alias);
        }
        return new ArrayList<>(result.values());
    }

    private String fingerprint(String company, String title, String cityCode) {
        return JobSearchFingerprint.of(company, title, cityCode);
    }

    private String normalizeCompany(String value) {
        String text = normalizeKey(value);
        for (String suffix : List.of("股份有限公司", "有限责任公司", "有限公司", "集团", "科技", "网络", "信息技术")) {
            if (text.endsWith(suffix) && text.length() > suffix.length()) {
                text = text.substring(0, text.length() - suffix.length());
            }
        }
        return text;
    }

    private String normalizeTitle(String value) {
        String text = normalizeKey(value);
        for (String suffix : List.of("急聘", "热招", "招聘", "高薪")) {
            if (text.endsWith(suffix) && text.length() > suffix.length()) {
                text = text.substring(0, text.length() - suffix.length());
            }
        }
        return text;
    }

    private String normalizeKey(String value) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[（(].*?[）)]", "")
                .replaceAll("[\\s\\-_/·,，。:：;；'\"“”‘’、|]+", "");
        return text;
    }

    private String limit(String value, int max, List<String> warnings, String field) {
        if (value == null) {
            return null;
        }
        if (value.length() > max) {
            warnings.add("TRUNCATED_" + field);
            return value.substring(0, max);
        }
        return value;
    }

    private String limitNullable(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private String cityCode(String value) {
        return value != null && value.matches("\\d{6}") ? value : null;
    }

    private String origin(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getScheme() + "://" + uri.getHost();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private boolean httpUrl(String value) {
        return JobSearchLinks.isHttpUrl(value);
    }

    private LocalDateTime time(Object value) {
        String text = text(value);
        if (text == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (RuntimeException ignored) {
            try {
                return LocalDateTime.parse(text);
            } catch (RuntimeException ex) {
                return null;
            }
        }
    }

    private static String iso(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }

    private BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && text.matches("-?\\d+")) {
            return Integer.parseInt(text);
        }
        return null;
    }

    private List<String> strings(Object value, int limit, int maxLength) {
        if (!(value instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            String text = text(item);
            if (text == null || text.isBlank()) {
                continue;
            }
            result.add(text.length() > maxLength ? text.substring(0, maxLength) : text);
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private static List<String> dedupe(List<String> values, int limit) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null && !result.contains(value)) {
                result.add(value);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String firstText(Object primary, Object fallback) {
        String value = text(primary);
        return value != null ? value : text(fallback);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(value instanceof List<?> list)) {
            return result;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record SourceDefaults(String type, String verification) {
    }

    private static final class SearchCandidate {
        private String sourceKey;
        private String sourceName;
        private String sourceType;
        private String verification;
        private String sourceUrl;
        private String recruitmentStatus;
        private String recruitmentEvidence;
        private LocalDateTime recruitmentCheckedAt;
        private String verificationStatus = "UNKNOWN";
        private String verificationReason;
        private String verificationEvidence;
        private LocalDateTime verificationCheckedAt;
        private Integer verificationHttpStatus;
        private List<Map<String, Object>> verificationHistory = new ArrayList<>();
        private List<String> searchProviders = new ArrayList<>();
        private boolean candidateTypeInferred;
        private String title;
        private String companyName;
        private String cityCode;
        private String cityName;
        private String candidateType;
        private String companySize;
        private Integer salaryMin;
        private Integer salaryMax;
        private String companySizeText;
        private Map<String, Object> fieldSources = new LinkedHashMap<>();
        private String salaryText;
        private String experienceText;
        private Integer experienceMonths;
        private String educationText;
        private String educationMin;
        private LocalDateTime publishedAt;
        private LocalDateTime updatedAt;
        private LocalDateTime fetchedAt;
        private String jdText;
        private Map<String, Object> requirements = Map.of();
        private BigDecimal semantic;
        private List<String> reasons = new ArrayList<>();
        private List<Map<String, Object>> gaps = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
        private List<Map<String, Object>> aliases = new ArrayList<>();
        private String fingerprint;
        private String roleCode;
        private String eligibilityStatus;
        private Map<String, Object> eligibility = Map.of();
        private Integer matchScore;
        private List<Map<String, Object>> components = new ArrayList<>();

        private void merge(SearchCandidate other) {
            int currentPriority = priority(sourceType);
            int otherPriority = priority(other.sourceType);
            boolean otherPrimary = otherPriority < currentPriority
                    || (otherPriority == currentPriority && other.jdText.length() > jdText.length());
            if (otherPrimary) {
                SearchCandidate swapped = copy();
                this.sourceKey = other.sourceKey;
                this.sourceName = other.sourceName;
                this.sourceType = other.sourceType;
                this.verification = other.verification;
                this.sourceUrl = other.sourceUrl;
                this.recruitmentStatus = other.recruitmentStatus;
                this.recruitmentEvidence = other.recruitmentEvidence;
                this.recruitmentCheckedAt = other.recruitmentCheckedAt;
                this.verificationStatus = other.verificationStatus;
                this.verificationReason = other.verificationReason;
                this.verificationEvidence = other.verificationEvidence;
                this.verificationCheckedAt = other.verificationCheckedAt;
                this.verificationHttpStatus = other.verificationHttpStatus;
                this.verificationHistory = other.verificationHistory;
                this.searchProviders = other.searchProviders;
                this.candidateTypeInferred = other.candidateTypeInferred;
                this.title = other.title;
                this.companyName = other.companyName;
                this.cityCode = other.cityCode;
                this.cityName = other.cityName;
                this.candidateType = other.candidateType;
                this.companySize = other.companySize;
                this.salaryMin = other.salaryMin;
                this.salaryMax = other.salaryMax;
                this.salaryText = other.salaryText;
                this.companySizeText = other.companySizeText;
                this.fieldSources = new LinkedHashMap<>(other.fieldSources);
                this.experienceText = other.experienceText;
                this.experienceMonths = other.experienceMonths;
                this.educationText = other.educationText;
                this.educationMin = other.educationMin;
                this.publishedAt = other.publishedAt;
                this.updatedAt = other.updatedAt;
                this.fetchedAt = other.fetchedAt;
                this.jdText = other.jdText;
                this.requirements = other.requirements;
                this.semantic = other.semantic;
                this.reasons = other.reasons;
                this.gaps = other.gaps;
                this.warnings = other.warnings;
                this.aliases = other.aliases;
                this.fingerprint = other.fingerprint;
                other = swapped;
            }
            fillMissing(other);
            warnings.addAll(other.warnings);
            warnings = dedupe(warnings, 20);
            List<Map<String, Object>> merged = new ArrayList<>(aliases);
            merged.addAll(other.aliases);
            aliases = dedupeAliases(merged);
            if (aliases.isEmpty()) {
                aliases.add(alias(this));
            }
            if (semantic == null) {
                semantic = other.semantic;
            }
        }

        private void fillMissing(SearchCandidate other) {
            if (cityCode == null) cityCode = other.cityCode;
            if (cityName == null) cityName = other.cityName;
            if (salaryText == null && salaryMin == null && salaryMax == null) {
                salaryText = other.salaryText; salaryMin = other.salaryMin; salaryMax = other.salaryMax;
                if (other.fieldSources.containsKey("salary")) fieldSources.put("salary", other.fieldSources.get("salary"));
            }
            if ("SIZE_UNKNOWN".equals(companySize)) {
                companySize = other.companySize; companySizeText = other.companySizeText;
                if (other.fieldSources.containsKey("companySize")) fieldSources.put("companySize", other.fieldSources.get("companySize"));
            }
            if (experienceText == null) experienceText = other.experienceText;
            if (experienceMonths == null) experienceMonths = other.experienceMonths;
            if (educationText == null) educationText = other.educationText;
            if (educationMin == null) educationMin = other.educationMin;
            if (publishedAt == null) publishedAt = other.publishedAt;
            if (updatedAt == null) updatedAt = other.updatedAt;
            if (fetchedAt == null) fetchedAt = other.fetchedAt;
            if (!"OFFICIAL".equals(sourceType) && other.verificationStatus != null
                    && verificationRank(other.verificationStatus) > verificationRank(verificationStatus)) {
                verificationStatus = other.verificationStatus;
                verificationReason = other.verificationReason;
                verificationEvidence = other.verificationEvidence;
                verificationCheckedAt = other.verificationCheckedAt;
                verificationHttpStatus = other.verificationHttpStatus;
                recruitmentStatus = other.recruitmentStatus;
                recruitmentEvidence = other.recruitmentEvidence;
                recruitmentCheckedAt = other.recruitmentCheckedAt;
            }
            for (String provider : other.searchProviders) {
                if (!searchProviders.contains(provider)) {
                    searchProviders.add(provider);
                }
            }
            if (verificationHistory.isEmpty()) {
                verificationHistory = other.verificationHistory;
            }
        }

        private SearchCandidate copy() {
            SearchCandidate copy = new SearchCandidate();
            copy.sourceKey = sourceKey;
            copy.sourceName = sourceName;
            copy.sourceType = sourceType;
            copy.companySizeText = companySizeText;
            copy.fieldSources = new LinkedHashMap<>(fieldSources);
            copy.verification = verification;
            copy.sourceUrl = sourceUrl;
            copy.recruitmentStatus = recruitmentStatus;
            copy.recruitmentEvidence = recruitmentEvidence;
            copy.recruitmentCheckedAt = recruitmentCheckedAt;
            copy.verificationStatus = verificationStatus;
            copy.verificationReason = verificationReason;
            copy.verificationEvidence = verificationEvidence;
            copy.verificationCheckedAt = verificationCheckedAt;
            copy.verificationHttpStatus = verificationHttpStatus;
            copy.verificationHistory = verificationHistory;
            copy.searchProviders = searchProviders;
            copy.candidateTypeInferred = candidateTypeInferred;
            copy.title = title;
            copy.companyName = companyName;
            copy.cityCode = cityCode;
            copy.cityName = cityName;
            copy.candidateType = candidateType;
            copy.companySize = companySize;
            copy.salaryMin = salaryMin;
            copy.salaryMax = salaryMax;
            copy.salaryText = salaryText;
            copy.experienceText = experienceText;
            copy.experienceMonths = experienceMonths;
            copy.educationText = educationText;
            copy.educationMin = educationMin;
            copy.publishedAt = publishedAt;
            copy.updatedAt = updatedAt;
            copy.fetchedAt = fetchedAt;
            copy.jdText = jdText;
            copy.requirements = requirements;
            copy.semantic = semantic;
            copy.reasons = reasons;
            copy.gaps = gaps;
            copy.warnings = warnings;
            copy.aliases = aliases;
            copy.fingerprint = fingerprint;
            return copy;
        }

        private static int priority(String type) {
            return switch (type == null ? "" : type) {
                case "OFFICIAL" -> 0;
                case "AUTHORIZED" -> 1;
                case "PUBLIC_SEARCH" -> 2;
                default -> 3;
            };
        }
    }
}
