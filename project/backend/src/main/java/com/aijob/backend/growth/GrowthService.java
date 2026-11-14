package com.aijob.backend.growth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.aijob.backend.ability.AbilityMapper;
import com.aijob.backend.ability.AbilityService;
import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.CommonResult;
import com.aijob.backend.common.Data;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.common.PageResult;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.task.TaskService;

@Service
public class GrowthService {

    private static final Set<String> SOURCE_TYPES = Set.of("RESUME_CLAIM", "LEARNING_ANSWER", "INTERVIEW_ANSWER");

    private final GrowthMapper mapper;
    private final AbilityService ability;
    private final AbilityMapper abilityMapper;
    private final ProfileService profiles;
    private final TaskService tasks;
    private final JsonService json;
    private final ApiView view;

    public GrowthService(GrowthMapper mapper, AbilityService ability, AbilityMapper abilityMapper,
                         ProfileService profiles, TaskService tasks, JsonService json, ApiView view) {
        this.mapper = mapper;
        this.ability = ability;
        this.abilityMapper = abilityMapper;
        this.profiles = profiles;
        this.tasks = tasks;
        this.json = json;
        this.view = view;
    }

    public Map<String, Object> summary(String userId) {
        long profileVersion = profileVersion(userId);
        // G03：画像版本变化后旧建议不再"隐身"，仍返回最新一条 READY 建议并标注依据版本与过期原因；
        // 生成新建议成功后（版本对齐）过期标注自然消失。
        Map<String, Object> advice = mapper.latestReadyAnyVersion(userId);
        List<String> suggestions = List.of();
        String suggestionTaskId = null;
        long suggestionProfileVersion = 0;
        boolean suggestionStale = false;
        String suggestionStaleReason = null;
        if (advice != null) {
            suggestions = json.convert(json.parse(String.valueOf(advice.get("suggestions_json"))), List.class);
            suggestionTaskId = String.valueOf(advice.get("task_id"));
            suggestionProfileVersion = ((Number) advice.get("profile_version")).longValue();
            if (suggestionProfileVersion != profileVersion) {
                suggestionStale = true;
                suggestionStaleReason = "该建议基于画像 v" + suggestionProfileVersion + " 生成，画像已更新至 v"
                        + profileVersion + "，建议内容可能已过时；可基于当前画像重新生成。";
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profileVersion", profileVersion);
        result.put("skills", skills(userId));
        result.put("recentEvidence", abilityMapper.recent(userId, 10).stream().map(this::evidenceVo).toList());
        result.put("suggestions", suggestions);
        result.put("suggestionTaskId", suggestionTaskId);
        result.put("suggestionProfileVersion", advice == null ? null : suggestionProfileVersion);
        result.put("suggestionStale", suggestionStale);
        result.put("suggestionStaleReason", suggestionStaleReason);
        return result;
    }

    public PageResult<Map<String, Object>> evidence(String userId, String skillCode, String sourceType,
                                                     Integer current, Integer size) {
        int page = Data.page(current);
        int pageSize = Data.size(size);
        if (skillCode != null) {
            profiles.requireSkill(userId, skillCode);
        }
        if (sourceType != null && !SOURCE_TYPES.contains(sourceType)) {
            throw BusinessException.validation("sourceType", "非法证据来源: " + sourceType);
        }
        List<Map<String, Object>> records = abilityMapper.evidencePage(userId, skillCode, sourceType, pageSize,
                Data.offset(current, size)).stream().map(this::evidenceVo).toList();
        return new PageResult<>(abilityMapper.evidenceCount(userId, skillCode, sourceType), records, page, pageSize);
    }

    public ResponseEntity<CommonResult<Object>> suggestions(String userId, String route, String key,
                                                             Map<String, Object> body) {
        ResponseEntity<CommonResult<Object>> replay = tasks.replay(userId, "POST", route, key, body);
        if (replay != null) {
            return replay;
        }
        long profileVersion = profileVersion(userId);
        Integer expected = Data.integer(body, "expectedProfileVersion", null, 1, Integer.MAX_VALUE);
        if (expected == null) {
            throw BusinessException.validation("expectedProfileVersion", "expectedProfileVersion 不能为空");
        }
        if (expected.longValue() != profileVersion) {
            throw BusinessException.versionConflict(profileVersion);
        }
        List<Map<String, Object>> evidence = abilityMapper.recent(userId, 100).stream()
                .filter(row -> "VALID".equals(String.valueOf(row.get("status"))))
                .map(this::evidenceVo).toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("profileVersion", profileVersion);
        payload.put("skills", skills(userId));
        payload.put("evidence", evidence);
        payload.put("targetJobRequirements", null);
        String adviceId = UUID.randomUUID().toString();
        return tasks.create(userId, "POST", route, key, body, "GROWTH_ANALYZE", "GROWTH", adviceId,
                Map.of("profileVersion", profileVersion), payload,
                taskId -> mapper.insertAdvice(adviceId, userId, profileVersion, taskId));
    }

    private long profileVersion(String userId) {
        return ((Number) profiles.get(userId).get("profileVersion")).longValue();
    }

    private List<Map<String, Object>> skills(String userId) {
        return ability.snapshots(userId).stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("skillCode", row.get("skill_code"));
            item.put("score", row.get("score"));
            item.put("sampleCount", row.get("sample_count"));
            item.put("evidenceStatus", row.get("evidence_status"));
            item.put("formulaVersion", row.get("formula_version"));
            item.put("updatedAt", view.value(row.get("updated_at")));
            return item;
        }).toList();
    }

    private Map<String, Object> evidenceVo(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.get("id"));
        item.put("skillCode", row.get("skill_code"));
        item.put("sourceType", row.get("source_type"));
        item.put("sourceId", row.get("source_id"));
        item.put("score", row.get("score"));
        item.put("confidence", row.get("confidence"));
        item.put("status", row.get("status"));
        item.put("rubricVersion", row.get("rubric_version"));
        item.put("createdAt", view.value(row.get("created_at")));
        return item;
    }
}
