package com.aijob.backend.job;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
public class JobResultHandler implements TaskResultHandler {

    private final JobMapper mapper;
    private final JobService jobs;
    private final JsonService json;
    private final EligibilityService eligibility;
    private final MatchScoreService scores;
    private final AbilityService ability;
    private final ProfileService profiles;

    public JobResultHandler(JobMapper mapper, JobService jobs, JsonService json, EligibilityService eligibility,
                            MatchScoreService scores, AbilityService ability, ProfileService profiles) {
        this.mapper = mapper;
        this.jobs = jobs;
        this.json = json;
        this.eligibility = eligibility;
        this.scores = scores;
        this.ability = ability;
        this.profiles = profiles;
    }

    @Override
    public Set<String> operations() {
        return Set.of("JD_ANALYZE", "JOB_MATCH");
    }

    @Override
    public int success(AiTask task, JsonNode result) {
        if ("JD_ANALYZE".equals(task.getOperation())) {
            return jd(task, result);
        }
        return match(task, result);
    }

    @Override
    public void failure(AiTask task, JsonNode error) {
        if ("JD_ANALYZE".equals(task.getOperation())) {
            mapper.updateJobStatus(task.getResourceId(), "FAILED");
        } else {
            Map<String, Object> batch = mapper.batch(task.getResourceId(), task.getUserId());
            if (batch != null) {
                mapper.updateBatch(task.getResourceId(), "FAILED", task.getId());
            }
        }
    }

    @Override
    public void prepareRetry(AiTask original, String newTaskId) {
        if ("JD_ANALYZE".equals(original.getOperation())) {
            mapper.updateJobStatus(original.getResourceId(), "ANALYZING");
        } else {
            mapper.updateBatch(original.getResourceId(), "GENERATING", newTaskId);
        }
    }

    @SuppressWarnings("unchecked")
    private int jd(AiTask task, JsonNode result) {
        Map<String, Object> job = mapper.lockJob(task.getResourceId(), task.getUserId());
        if (job == null || !"ANALYZING".equals(String.valueOf(job.get("status")))) {
            throw BusinessException.conflict("岗位状态已变化", "INVALID_STATE");
        }
        Map<String, Object> value = json.map(result);
        Map<String, Object> requirements = (Map<String, Object>) value.get("requirements");
        if (requirements == null) {
            throw BusinessException.aiSchema("JD 分析结果缺少 requirements");
        }
        for (Object code : (List<Object>) requirements.getOrDefault("skills", List.of())) {
            try {
                profiles.requireSkill(String.valueOf(code));
            } catch (BusinessException ex) {
                throw BusinessException.aiSchema("JD 分析返回未知技能编码");
            }
        }
        Integer min = null;
        Integer max = null;
        if (value.get("salary") instanceof Map<?, ?> salary) {
            Object minValue = salary.get("minMonthlySalaryCents");
            Object maxValue = salary.get("maxMonthlySalaryCents");
            if (!(minValue instanceof Number) || !(maxValue instanceof Number)) {
                throw BusinessException.aiSchema("JD 薪资格式非法");
            }
            min = ((Number) minValue).intValue();
            max = ((Number) maxValue).intValue();
            if (min < 0 || max < min) {
                throw BusinessException.aiSchema("JD 薪资范围非法");
            }
        }
        int version = ((Number) job.get("job_version")).intValue();
        mapper.analyzed(task.getResourceId(), version, json.stringify(requirements), json.stringify(value), min, max,
                jobs.roleCode(String.valueOf(job.get("title")) + " " + String.valueOf(job.get("jd_text"))));
        mapper.updateJobStatus(task.getResourceId(), "ACTIVE");
        return version;
    }

    @SuppressWarnings("unchecked")
    private int match(AiTask task, JsonNode result) {
        Map<String, Object> batch = mapper.lockBatch(task.getResourceId(), task.getUserId());
        if (batch == null || !"GENERATING".equals(String.valueOf(batch.get("status")))) {
            throw BusinessException.conflict("匹配批次状态已变化", "INVALID_STATE");
        }
        Map<String, Object> payload = json.map(task.getInputSnapshotJson());
        Map<String, Object> profile = (Map<String, Object>) payload.get("profile");
        Map<String, Object> facts = (Map<String, Object>) profile.get("facts");
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) payload.get("candidates");
        List<Map<String, Object>> agentItems = (List<Map<String, Object>>) json.map(result).get("items");
        if (agentItems == null || agentItems.size() != candidates.size()) {
            throw BusinessException.aiSchema("匹配结果候选集合与输入不一致");
        }
        Map<String, Map<String, Object>> byJob = new HashMap<>();
        for (Map<String, Object> item : agentItems) {
            String jobId = String.valueOf(item.get("jobId"));
            if (byJob.put(jobId, item) != null) {
                throw BusinessException.aiSchema("匹配结果包含重复岗位");
            }
        }
        Map<String, Map<String, Object>> jobSnapshots = ((List<Map<String, Object>>) json.map(
                String.valueOf(batch.get("input_snapshot_json"))).get("jobs")).stream()
                .collect(Collectors.toMap(item -> String.valueOf(item.get("id")), Function.identity()));
        List<Map<String, Object>> fixedSnapshots = (List<Map<String, Object>>) payload.getOrDefault("skillSnapshot", List.of());
        Map<String, Map<String, Object>> snapshots = fixedSnapshots.stream().map(item -> {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("skill_code", item.get("skillCode"));
            snapshot.put("score", item.get("score"));
            snapshot.put("sample_count", item.get("sampleCount"));
            return snapshot;
        }).collect(Collectors.toMap(item -> String.valueOf(item.get("skill_code")), Function.identity()));
        List<PreparedItem> prepared = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            String jobId = String.valueOf(candidate.get("jobId"));
            Map<String, Object> agent = byJob.remove(jobId);
            int jobVersion = ((Number) candidate.get("jobVersion")).intValue();
            if (agent == null || ((Number) agent.get("jobVersion")).intValue() != jobVersion) {
                throw BusinessException.aiSchema("匹配结果缺少候选或岗位版本不一致");
            }
            Map<String, Object> requirements = (Map<String, Object>) candidate.get("requirements");
            Map<String, Object> eligibilityResult = eligibility.evaluate(facts, requirements);
            BigDecimal semantic = agent.get("semanticScore") == null ? null : decimal(agent.get("semanticScore"));
            if (semantic != null && (semantic.compareTo(BigDecimal.ZERO) < 0 || semantic.compareTo(BigDecimal.ONE) > 0)) {
                throw BusinessException.aiSchema("semanticScore 超出 0..1");
            }
            List<String> requiredSkills = strings((List<Object>) requirements.getOrDefault("skills", List.of()));
            List<String> confirmedSkills = ((List<Map<String, Object>>) facts.getOrDefault("skills", List.of())).stream()
                    .map(item -> String.valueOf(item.get("skillCode"))).toList();
            BigDecimal skill = scores.skill(confirmedSkills, requiredSkills);
            Map<String, Object> jobSnapshot = jobSnapshots.get(jobId);
            BigDecimal preference = scores.preference(String.valueOf(profile.get("targetRole")),
                    jobSnapshot.get("roleCode") == null ? null : String.valueOf(jobSnapshot.get("roleCode")),
                    (List<String>) profile.get("cities"), jobSnapshot.get("cityCode") == null ? null : String.valueOf(jobSnapshot.get("cityCode")));
            Integer total = scores.score(skill, semantic, preference);
            String semanticVersion = agent.get("semanticVersion") == null ? "semantic-v1" : String.valueOf(agent.get("semanticVersion"));
            List<Map<String, Object>> components = List.of(
                    scores.component("skill", skill, BigDecimal.valueOf(0.60), "confirmed-resume-v1"),
                    scores.component("semantic", semantic, BigDecimal.valueOf(0.25), semanticVersion),
                    scores.component("preference", preference, BigDecimal.valueOf(0.15), "profile-preferences-v1"));
            List<Map<String, Object>> gaps = gaps((List<Map<String, Object>>) agent.getOrDefault("gaps", List.of()),
                    requiredSkills, requirements, snapshots);
            List<String> reasons = strings((List<Object>) agent.getOrDefault("reasons", List.of()));
            String eligibilityStatus = String.valueOf(eligibilityResult.get("status"));
            prepared.add(new PreparedItem(jobId, jobVersion, eligibilityStatus, eligibilityResult,
                    "FAIL".equals(eligibilityStatus) ? null : total, components, gaps, reasons, jobSnapshot));
        }
        if (!byJob.isEmpty()) {
            throw BusinessException.aiSchema("匹配结果包含输入外岗位");
        }
        prepared.sort(Comparator.comparingInt((PreparedItem item) -> switch (item.eligibilityStatus) {
            case "PASS" -> 0;
            case "UNKNOWN" -> 1;
            default -> 2;
        }).thenComparing(item -> item.matchScore, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(item -> item.jobId));
        int rank = 1;
        for (PreparedItem item : prepared) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("title", item.jobSnapshot.get("title"));
            source.put("source", item.jobSnapshot.get("source"));
            source.put("sourceUrl", item.jobSnapshot.get("sourceUrl"));
            source.put("verifiedAt", item.jobSnapshot.get("verifiedAt"));
            mapper.insertItem(UUID.randomUUID().toString(), task.getResourceId(), item.jobId, item.jobVersion, rank++,
                    item.eligibilityStatus, json.stringify(item.eligibility), item.matchScore,
                    json.stringify(item.components), json.stringify(item.gaps), json.stringify(item.reasons), json.stringify(source));
        }
        mapper.updateBatch(task.getResourceId(), "READY", task.getId());
        return 1;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> gaps(List<Map<String, Object>> agentGaps, List<String> allowedSkills,
                                           Map<String, Object> requirements,
                                           Map<String, Map<String, Object>> snapshots) {
        Map<String, String> suggestions = new HashMap<>();
        for (Map<String, Object> gap : agentGaps) {
            String code = String.valueOf(gap.get("skillCode"));
            if (allowedSkills.contains(code)) {
                suggestions.put(code, gap.get("suggestion") == null ? "建议补充有效练习证据" : String.valueOf(gap.get("suggestion")));
            }
        }
        Map<String, Object> levels = (Map<String, Object>) requirements.getOrDefault("requiredSkillLevels", Map.of());
        List<Map<String, Object>> result = new ArrayList<>();
        for (String code : allowedSkills) {
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
            item.put("suggestion", suggestions.getOrDefault(code, current == null ? "证据不足，建议完成有效评价" : "继续针对岗位要求练习"));
            result.add(item);
        }
        return result;
    }

    private BigDecimal decimal(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    private List<String> strings(List<Object> values) {
        return values.stream().map(String::valueOf).toList();
    }

    private record PreparedItem(String jobId, int jobVersion, String eligibilityStatus, Map<String, Object> eligibility,
                                Integer matchScore, List<Map<String, Object>> components, List<Map<String, Object>> gaps,
                                List<String> reasons, Map<String, Object> jobSnapshot) {
    }
}