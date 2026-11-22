package com.aijob.backend.resume;

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
public class ResumeResultHandler implements TaskResultHandler {

    private final ResumeMapper mapper;
    private final JsonService json;

    public ResumeResultHandler(ResumeMapper mapper, JsonService json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    public Set<String> operations() {
        return Set.of("RESUME_PARSE");
    }

    @Override
    @SuppressWarnings("unchecked")
    public int success(AiTask task, JsonNode result) {
        Map<String, Object> row = mapper.lockVersion(task.getResourceId(), task.getUserId());
        if (row == null || !"PARSING".equals(String.valueOf(row.get("status")))) {
            throw BusinessException.conflict("简历版本状态已变化", "INVALID_STATE");
        }
        Map<String, Object> parsed = json.map(result);
        String textHash = String.valueOf(parsed.get("textHash"));
        String canonicalText = String.valueOf(row.get("extracted_text"))
                .replace("\r\n", "\n").replace('\r', '\n').strip();
        if (!json.sha256(canonicalText).equalsIgnoreCase(textHash)) {
            throw BusinessException.aiSchema("解析结果 textHash 与文件抽取文本不一致");
        }
        Map<String, Object> facts = (Map<String, Object>) parsed.get("facts");
        if (facts == null) {
            throw BusinessException.aiSchema("解析结果缺少 facts");
        }
        // 空事实拒绝：skills/internships/awards 全为空时不得标记为解析完成（此前空结果被标成 PARSED）。
        List<Map<String, Object>> skills = (List<Map<String, Object>>) facts.getOrDefault("skills", List.of());
        List<Map<String, Object>> internships = (List<Map<String, Object>>) facts.getOrDefault("internships", List.of());
        List<Map<String, Object>> awards = (List<Map<String, Object>>) facts.getOrDefault("awards", List.of());
        if (skills.isEmpty() && internships.isEmpty() && awards.isEmpty()) {
            throw BusinessException.aiSchema("解析结果没有任何有效事实（技能、实习、获奖均为空）");
        }
        for (Map<String, Object> skill : skills) {
            skill.put("confirmed", false);
        }
        for (String field : List.of("internships", "awards")) {
            List<Map<String, Object>> evidence = (List<Map<String, Object>>) facts.getOrDefault(field, List.of());
            for (Map<String, Object> item : evidence) item.put("confirmed", false);
        }
        if (mapper.parsed(task.getResourceId(), json.stringify(facts), json.stringify(parsed)) == 0) {
            throw BusinessException.conflict("简历版本状态已变化", "INVALID_STATE");
        }
        return ((Number) row.get("version_no")).intValue();
    }

    @Override
    public void failure(AiTask task, JsonNode error) {
        Map<String, Object> row = mapper.version(task.getResourceId(), task.getUserId());
        if (row != null && Set.of("PARSING", "UPLOADED").contains(String.valueOf(row.get("status")))) {
            // 重跑失败保留草稿：已有有效草稿（reparse 场景）时恢复 PARSED，避免丢失原解析结果。
            String existingFacts = row.get("parsed_facts_json") == null ? null
                    : String.valueOf(row.get("parsed_facts_json"));
            if ("PARSING".equals(String.valueOf(row.get("status"))) && existingFacts != null && !existingFacts.isBlank()) {
                mapper.updateStatus(task.getResourceId(), "PARSED");
            } else {
                mapper.updateStatus(task.getResourceId(), "FAILED");
            }
        }
    }

    @Override
    public void prepareRetry(AiTask original, String newTaskId) {
        mapper.updateStatus(original.getResourceId(), "PARSING");
    }
}
