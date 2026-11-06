package com.aijob.backend.growth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.task.AiTask;
import com.aijob.backend.task.TaskResultHandler;

import tools.jackson.databind.JsonNode;

@Component
public class GrowthResultHandler implements TaskResultHandler {

    private final GrowthMapper mapper;
    private final JsonService json;

    public GrowthResultHandler(GrowthMapper mapper, JsonService json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    public Set<String> operations() {
        return Set.of("GROWTH_ANALYZE");
    }

    @Override
    public int success(AiTask task, JsonNode result) {
        Map<String, Object> advice = mapper.lockAdvice(task.getResourceId(), task.getUserId());
        if (advice == null || !"GENERATING".equals(String.valueOf(advice.get("status")))
                || !task.getId().equals(String.valueOf(advice.get("task_id")))) {
            throw BusinessException.conflict("成长建议状态已变化", "INVALID_STATE");
        }
        Map<String, Object> value = json.map(result);
        Object rawSuggestions = value.get("suggestions");
        if (!(rawSuggestions instanceof List<?> suggestionValues)
                || suggestionValues.isEmpty() || suggestionValues.size() > 5) {
            throw BusinessException.aiSchema("成长建议必须包含 1..5 条字符串");
        }
        List<String> suggestions = new ArrayList<>();
        for (Object raw : suggestionValues) {
            if (!(raw instanceof String suggestion) || suggestion.isBlank()) {
                throw BusinessException.aiSchema("成长建议必须包含 1..5 条字符串");
            }
            suggestions.add(suggestion);
        }
        Map<String, Object> payload = json.map(task.getInputSnapshotJson());
        Object rawEvidence = payload.get("evidence");
        if (!(rawEvidence instanceof List<?> evidenceValues)) {
            throw BusinessException.aiSchema("任务输入证据快照非法");
        }
        Set<String> allowedEvidenceIds = new HashSet<>();
        for (Object raw : evidenceValues) {
            if (raw instanceof Map<?, ?> evidence && evidence.get("id") != null) {
                allowedEvidenceIds.add(String.valueOf(evidence.get("id")));
            }
        }
        Object rawCited = value.getOrDefault("citedEvidenceIds", List.of());
        if (!(rawCited instanceof List<?> citedValues)) {
            throw BusinessException.aiSchema("成长建议引用证据格式非法");
        }
        List<String> citedEvidenceIds = new ArrayList<>();
        for (Object raw : citedValues) {
            if (!(raw instanceof String evidenceId) || !allowedEvidenceIds.contains(evidenceId)) {
                throw BusinessException.aiSchema("成长建议引用了输入外证据");
            }
            citedEvidenceIds.add(evidenceId);
        }
        if (mapper.ready(task.getResourceId(), task.getUserId(), task.getId(), json.stringify(suggestions),
                json.stringify(citedEvidenceIds)) == 0) {
            throw BusinessException.conflict("成长建议状态已变化", "INVALID_STATE");
        }
        return 1;
    }

    @Override
    public void failure(AiTask task, JsonNode error) {
        mapper.fail(task.getResourceId(), task.getUserId(), task.getId());
    }

    @Override
    public void prepareRetry(AiTask original, String newTaskId) {
        if (mapper.prepareRetry(original.getResourceId(), original.getUserId(), original.getId(), newTaskId) == 0) {
            throw BusinessException.conflict("成长建议状态已变化", "INVALID_STATE");
        }
    }
}