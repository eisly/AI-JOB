package com.aijob.backend.project;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.task.AiTask;
import com.aijob.backend.task.TaskResultHandler;

import tools.jackson.databind.JsonNode;

@Component
public class ProjectResultHandler implements TaskResultHandler {

    private final ProjectMapper mapper;
    private final JsonService json;

    public ProjectResultHandler(ProjectMapper mapper, JsonService json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    public Set<String> operations() {
        return Set.of("PROJECT_RECOMMEND", "PROJECT_PLAN");
    }

    @Override
    public int success(AiTask task, JsonNode result) {
        return "PROJECT_RECOMMEND".equals(task.getOperation()) ? recommend(task, result) : plan(task, result);
    }

    @Override
    public void failure(AiTask task, JsonNode error) {
        if ("PROJECT_RECOMMEND".equals(task.getOperation())) {
            if (mapper.recommendation(task.getResourceId(), task.getUserId()) != null) {
                mapper.updateRecommendation(task.getResourceId(), "FAILED", task.getId());
            }
        } else if (mapper.project(task.getResourceId(), task.getUserId()) != null) {
            mapper.updateProjectTask(task.getResourceId(), "FAILED", task.getId());
        }
    }

    @Override
    public void prepareRetry(AiTask original, String newTaskId) {
        if ("PROJECT_RECOMMEND".equals(original.getOperation())) {
            mapper.updateRecommendation(original.getResourceId(), "GENERATING", newTaskId);
        } else {
            mapper.updateProjectTask(original.getResourceId(), "GENERATING", newTaskId);
        }
    }

    private int recommend(AiTask task, JsonNode result) {
        Map<String, Object> recommendation = mapper.lockRecommendation(task.getResourceId(), task.getUserId());
        if (recommendation == null || !"GENERATING".equals(String.valueOf(recommendation.get("status")))) {
            throw BusinessException.conflict("项目推荐状态已变化", "INVALID_STATE");
        }
        Map<String, Object> payload = json.map(task.getInputSnapshotJson());
        List<String> allowedSkills = strings(payload.get("allowedSkillCodes"), 1, 20, "allowedSkillCodes");
        String difficulty = requiredText(payload, "difficulty", 16);
        int maxDurationWeeks = requiredInt(payload.get("maxDurationWeeks"), 1, 12);
        List<Map<String, Object>> items = objects(json.map(result).get("items"), 1, 6, "items");
        List<Map<String, Object>> saved = new ArrayList<>();
        for (Map<String, Object> raw : items) {
            String title = requiredText(raw, "title", 200);
            String summary = requiredText(raw, "summary", 2000);
            List<String> skillCodes = strings(raw.get("skillCodes"), 1, 10, "skillCodes");
            if (!allowedSkills.containsAll(skillCodes)) {
                throw BusinessException.aiSchema("项目推荐包含输入外技能");
            }
            if (!difficulty.equals(requiredText(raw, "difficulty", 16))) {
                throw BusinessException.aiSchema("项目推荐难度与输入不一致");
            }
            int estimatedWeeks = requiredInt(raw.get("estimatedWeeks"), 1, maxDurationWeeks);
            String reason = requiredText(raw, "reason", 2000);
            List<String> deliverables = strings(raw.get("deliverables"), 1, 10, "deliverables");
            String sourceType = optionalText(raw, "sourceType", 32, "AI_GENERATED");
            String source = optionalText(raw, "source", 32, "MODEL");
            Map<String, Object> repository = null;
            if ("GITHUB_REPOSITORY".equals(sourceType)) {
                if (!"GITHUB_API".equals(source)) {
                    throw BusinessException.aiSchema("真实仓库项目必须来自 GitHub API");
                }
                repository = githubRepository(raw.get("repository"));
                List<?> matched = (List<?>) repository.get("matchedSkillCodes");
                List<String> actual = allowedSkills.stream().filter(code -> matched.stream()
                        .anyMatch(value -> code.equalsIgnoreCase(String.valueOf(value)))).toList();
                if (actual.isEmpty() || !Set.copyOf(actual).equals(Set.copyOf(skillCodes))) {
                    throw BusinessException.aiSchema("真实仓库技能必须与实际匹配的目标技能一致");
                }
            } else if ("AI_GENERATED".equals(sourceType)) {
                if (!Set.of("MODEL", "LOCAL_RULES").contains(source)) {
                    throw BusinessException.aiSchema("AI 练习项目来源非法");
                }
                if (raw.get("repository") != null) {
                    throw BusinessException.aiSchema("AI 练习项目不能携带仓库信息");
                }
            } else {
                throw BusinessException.aiSchema("项目推荐来源类型非法");
            }
            String itemId = UUID.randomUUID().toString();
            mapper.insertRecommendationItem(itemId, task.getResourceId(), task.getUserId(), title, summary,
                    json.stringify(skillCodes), difficulty, estimatedWeeks, reason, json.stringify(deliverables),
                    sourceType, source, repository == null ? null : json.stringify(repository));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", itemId);
            item.put("title", title);
            item.put("summary", summary);
            item.put("skillCodes", skillCodes);
            item.put("difficulty", difficulty);
            item.put("estimatedWeeks", estimatedWeeks);
            item.put("reason", reason);
            item.put("deliverables", deliverables);
            item.put("sourceType", sourceType);
            item.put("source", source);
            item.put("repository", repository);
            saved.add(item);
        }
        List<String> covered = allowedSkills.stream().filter(code -> saved.stream()
                .anyMatch(item -> ((List<?>) item.get("skillCodes")).contains(code))).toList();
        if (!covered.containsAll(allowedSkills)) {
            throw BusinessException.aiSchema("推荐候选集合未覆盖全部目标技能");
        }
        Map<String, Object> snapshot = Map.of("targetSkillCodes", allowedSkills, "items", saved);
        if (mapper.completeRecommendation(task.getResourceId(), "READY", task.getId(), json.stringify(snapshot)) == 0) {
            throw BusinessException.conflict("项目推荐状态已变化", "INVALID_STATE");
        }
        return 1;
    }

    private int plan(AiTask task, JsonNode result) {
        Map<String, Object> project = mapper.lockProject(task.getResourceId(), task.getUserId());
        if (project == null || !"GENERATING".equals(String.valueOf(project.get("status")))) {
            throw BusinessException.conflict("项目状态已变化", "INVALID_STATE");
        }
        Map<String, Object> payload = json.map(task.getInputSnapshotJson());
        Object rawRecommendation = payload.get("recommendation");
        if (!(rawRecommendation instanceof Map<?, ?>)) {
            throw BusinessException.aiSchema("项目计划缺少推荐快照");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> recommendation = (Map<String, Object>) rawRecommendation;
        int estimatedWeeks = requiredInt(recommendation.get("estimatedWeeks"), 1, 12);
        int weeklyHours = requiredInt(payload.get("weeklyHours"), 1, 40);
        Map<String, Object> value = json.map(result);
        String title = requiredText(value, "title", 200);
        String summary = requiredText(value, "summary", 2000);
        List<Map<String, Object>> milestones = objects(value.get("milestones"), 1, 8, "milestones");
        int totalHours = 0;
        int expectedSeq = 1;
        for (Map<String, Object> milestone : milestones) {
            int seq = requiredInt(milestone.get("seq"), 1, 8);
            if (seq != expectedSeq++) {
                throw BusinessException.aiSchema("里程碑 seq 必须从 1 开始连续编号");
            }
            String milestoneId = UUID.randomUUID().toString();
            mapper.insertMilestone(milestoneId, task.getResourceId(), seq, requiredText(milestone, "title", 200),
                    requiredText(milestone, "description", 2000));
            List<Map<String, Object>> planTasks = objects(milestone.get("tasks"), 1, 10, "tasks");
            for (Map<String, Object> planTask : planTasks) {
                int hours = requiredInt(planTask.get("estimatedHours"), 1, 40);
                totalHours += hours;
                mapper.insertTask(UUID.randomUUID().toString(), task.getResourceId(), milestoneId,
                        requiredText(planTask, "title", 200), requiredText(planTask, "description", 2000),
                        BigDecimal.valueOf(hours));
            }
        }
        if (totalHours > weeklyHours * estimatedWeeks) {
            throw BusinessException.aiSchema("项目计划总工时超过输入上限");
        }
        if (mapper.completeProject(task.getResourceId(), title, summary, "ACTIVE", task.getId()) == 0) {
            throw BusinessException.conflict("项目状态已变化", "INVALID_STATE");
        }
        return ((Number) project.get("version")).intValue();
    }

    private String requiredText(Map<String, Object> value, String field, int max) {
        Object raw = value.get(field);
        if (!(raw instanceof String text) || text.isBlank() || text.length() > max) {
            throw BusinessException.aiSchema(field + " 缺失或长度非法");
        }
        return text.strip();
    }

    private String optionalText(Map<String, Object> value, String field, int max, String fallback) {
        Object raw = value.get(field);
        if (raw == null) {
            return fallback;
        }
        return requiredText(value, field, max);
    }

    private Map<String, Object> githubRepository(Object raw) {
        if (!(raw instanceof Map<?, ?> source)) {
            throw BusinessException.aiSchema("真实仓库项目缺少 repository");
        }
        Map<String, Object> repository = new LinkedHashMap<>();
        repository.put("fullName", textValue(source, "fullName", 200));
        String htmlUrl = textValue(source, "htmlUrl", 2048);
        try {
            URI uri = URI.create(htmlUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())) {
                throw BusinessException.aiSchema("GitHub 仓库链接必须是 github.com 的 HTTPS 地址");
            }
        } catch (IllegalArgumentException ex) {
            throw BusinessException.aiSchema("GitHub 仓库链接格式非法");
        }
        repository.put("htmlUrl", htmlUrl);
        repository.put("description", textValue(source, "description", 1000));
        repository.put("stargazersCount", nonNegativeInt(source.get("stargazersCount"), "stargazersCount"));
        Object language = source.get("language");
        if (language != null && (!(language instanceof String) || ((String) language).length() > 64)) {
            throw BusinessException.aiSchema("GitHub 仓库语言字段非法");
        }
        repository.put("language", language);
        repository.put("pushedAt", textValue(source, "pushedAt", 64));
        repository.put("topics", strings(source.get("topics"), 0, 20, "topics"));
        repository.put("matchedSkillCodes", strings(source.get("matchedSkillCodes"), 0, 10, "matchedSkillCodes"));
        if (!"GITHUB_API".equals(textValue(source, "source", 32))) {
            throw BusinessException.aiSchema("GitHub 仓库来源标记非法");
        }
        repository.put("source", "GITHUB_API");
        repository.put("verifiedAt", textValue(source, "verifiedAt", 64));
        return repository;
    }

    private String textValue(Map<?, ?> value, String field, int max) {
        Object raw = value.get(field);
        if (!(raw instanceof String text) || text.isBlank() || text.length() > max) {
            throw BusinessException.aiSchema("GitHub 仓库字段 " + field + " 缺失或长度非法");
        }
        return text.strip();
    }

    private int nonNegativeInt(Object value, String field) {
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue() || number.intValue() < 0) {
            throw BusinessException.aiSchema("GitHub 仓库字段 " + field + " 非法");
        }
        return number.intValue();
    }

    private int requiredInt(Object value, int min, int max) {
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()
                || number.intValue() < min || number.intValue() > max) {
            throw BusinessException.aiSchema("数值字段超出有效范围");
        }
        return number.intValue();
    }

    private List<String> strings(Object raw, int min, int max, String field) {
        if (!(raw instanceof List<?> values) || values.size() < min || values.size() > max) {
            throw BusinessException.aiSchema(field + " 数组长度非法");
        }
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof String text) || text.isBlank()) {
                throw BusinessException.aiSchema(field + " 必须为非空字符串数组");
            }
            result.add(text.strip());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> objects(Object raw, int min, int max, String field) {
        if (!(raw instanceof List<?> values) || values.size() < min || values.size() > max) {
            throw BusinessException.aiSchema(field + " 数组长度非法");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?>)) {
                throw BusinessException.aiSchema(field + " 必须为对象数组");
            }
            result.add((Map<String, Object>) value);
        }
        return result;
    }
}
