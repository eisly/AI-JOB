package com.aijob.backend.project;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    private static final Set<String> PLATFORMS = Set.of("GITHUB", "GITEE", "GITCODE");
    private static final Set<String> PLATFORM_STATUSES = Set.of("SUCCEEDED", "FAILED");
    private static final Set<String> DIFFICULTIES = Set.of("BEGINNER", "INTERMEDIATE", "ADVANCED");
    private static final Map<String, String> PLATFORM_HOSTS = Map.of(
            "GITHUB", "github.com", "GITEE", "gitee.com", "GITCODE", "gitcode.com");

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
            mapper.markRepositoryMode(original.getResourceId());
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
        String inputDifficulty = optionalText(payload, "difficulty", 16);
        if (inputDifficulty != null && !DIFFICULTIES.contains(inputDifficulty)) {
            throw BusinessException.aiSchema("输入项目难度非法");
        }
        int maxDurationWeeks = requiredInt(payload.get("maxDurationWeeks"), 1, 12);
        Map<String, Object> value = json.map(result);
        boolean discovery = payload.get("discoveryRound") != null && recommendation.get("discovery_round") != null;
        List<String> keywords = discovery ? strings(payload.get("techStackKeywords"), 1, 5, "techStackKeywords") : List.of();
        List<Map<String, Object>> sourceRuns = sourceRuns(value.get("sourceRuns"));
        List<Map<String, Object>> items = value.get("items") == null
                ? List.of() : objects(value.get("items"), 0, discovery ? 24 : 6, "items");
        List<Map<String, Object>> saved = new ArrayList<>();
        for (Map<String, Object> raw : items) {
            String title = requiredText(raw, "title", 200);
            String summary = requiredText(raw, "summary", 2000);
            List<String> skillCodes = strings(raw.get("skillCodes"), 1, 10, "skillCodes");
            if (!allowedSkills.containsAll(skillCodes)) {
                throw BusinessException.aiSchema("项目推荐包含输入外技能");
            }
            String difficulty = requiredText(raw, "difficulty", 16);
            if (!DIFFICULTIES.contains(difficulty)
                    || (inputDifficulty != null && !inputDifficulty.equals(difficulty))) {
                throw BusinessException.aiSchema("项目推荐难度与输入不一致");
            }
            int estimatedWeeks = requiredInt(raw.get("estimatedWeeks"), 1, maxDurationWeeks);
            String reason = requiredText(raw, "reason", 2000);
            List<String> gettingStarted = strings(raw.get("gettingStarted"), 1, 5, "gettingStarted");
            List<String> deliverables = strings(raw.get("deliverables"), 1, 10, "deliverables");
            Map<String, Object> primarySource = source(raw.get("primarySource"), "primarySource");
            List<Map<String, Object>> alternateSources = sources(raw.get("alternateSources"));
            String itemId = UUID.randomUUID().toString();
            mapper.insertRecommendationItem(itemId, task.getResourceId(), task.getUserId(), title, summary,
                    json.stringify(skillCodes), difficulty, estimatedWeeks, reason,
                    json.stringify(gettingStarted), json.stringify(primarySource),
                    json.stringify(alternateSources), json.stringify(deliverables));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", itemId);
            item.put("title", title);
            item.put("summary", summary);
            item.put("skillCodes", skillCodes);
            item.put("difficulty", difficulty);
            item.put("estimatedWeeks", estimatedWeeks);
            item.put("reason", reason);
            item.put("gettingStarted", gettingStarted);
            item.put("deliverables", deliverables);
            item.put("primarySource", primarySource);
            item.put("alternateSources", alternateSources);
            if (discovery) {
                item.put("stackEvidence", stackEvidence(raw.get("stackEvidence"), keywords, primarySource));
            }
            saved.add(item);
        }
        if (mapper.completeRecommendation(task.getResourceId(), "READY", task.getId(), json.stringify(saved),
                json.stringify(sourceRuns)) == 0) {
            throw BusinessException.conflict("项目推荐状态已变化", "INVALID_STATE");
        }
        if (discovery) {
            mapper.saveSearchState(task.getResourceId(), json.stringify(searchState(value)));
        }
        return 1;
    }

    private Map<String, Object> searchState(Map<String, Object> result) {
        if (!(result.get("searchCursor") instanceof Map<?, ?> cursor) || cursor.size() > 9) {
            throw BusinessException.aiSchema("searchCursor 非法");
        }
        for (Map.Entry<?, ?> entry : cursor.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !key.matches("(GITHUB|GITEE|GITCODE):[0-2]")) {
                throw BusinessException.aiSchema("searchCursor 平台或检索流非法");
            }
            requiredInt(entry.getValue(), -100, 126);
        }
        List<String> keys = strings(result.get("discoveredKeys"), 0, 72, "discoveredKeys");
        for (String key : keys) {
            int split = key.indexOf(':');
            if (split < 0 || key.length() > 308 || !PLATFORMS.contains(key.substring(0, split))
                    || !validRepositoryPath(key.substring(split + 1), key.substring(0, split))) {
                throw BusinessException.aiSchema("discoveredKeys 仓库标识非法");
            }
        }
        return Map.of("cursor", cursor, "keys", keys);
    }

    private List<Map<String, Object>> stackEvidence(Object raw, List<String> keywords, Map<String, Object> repository) {
        List<Map<String, Object>> evidence = objects(raw, keywords.size(), keywords.size(), "stackEvidence");
        Set<String> missing = new HashSet<>(keywords);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : evidence) {
            String keyword = requiredText(item, "keyword", 64);
            String status = requiredText(item, "status", 16);
            String url = requiredText(item, "url", 2048);
            String path = requiredText(item, "path", 240);
            String repoPath = String.valueOf(repository.get("path"));
            String platform = String.valueOf(repository.get("platform"));
            if (!missing.remove(keyword) || !Set.of("VERIFIED", "DOCUMENTED").contains(status)
                    || !path.matches("(?U)[\\w./-]+") || !repositoryUrlMatches(url, platform, repoPath, true)) {
                throw BusinessException.aiSchema("技术栈证据与筛选条件或仓库不一致");
            }
            URI uri = URI.create(url);
            String decoded = uri.getPath();
            if (uri.getQuery() != null || uri.getFragment() != null
                    || !decoded.toLowerCase(Locale.ROOT).startsWith("/" + repoPath.toLowerCase(Locale.ROOT) + "/blob/")
                    || !decoded.endsWith("/" + path) || !uri.normalize().getPath().equals(decoded)
                    || List.of(decoded.split("/")).contains("..")) {
                throw BusinessException.aiSchema("技术栈证据文件路径非法");
            }
            result.add(Map.of("keyword", keyword, "status", status, "path", path, "url", url));
        }
        return result;
    }

    private List<Map<String, Object>> sourceRuns(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> values) || values.size() > 3) {
            throw BusinessException.aiSchema("sourceRuns 数组长度非法");
        }
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?>)) {
                throw BusinessException.aiSchema("sourceRuns 必须为对象数组");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> source = (Map<String, Object>) value;
            String platform = requiredText(source, "platform", 16);
            if (!PLATFORMS.contains(platform) || !seen.add(platform)) {
                throw BusinessException.aiSchema("sourceRuns 平台非法或重复");
            }
            String status = requiredText(source, "status", 16);
            if (!PLATFORM_STATUSES.contains(status)) {
                throw BusinessException.aiSchema("sourceRuns 状态非法");
            }
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("platform", platform);
            run.put("status", status);
            run.put("reason", optionalText(source, "reason", 500));
            result.add(run);
        }
        return result;
    }

    private List<Map<String, Object>> sources(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> values) || values.size() > 5) {
            throw BusinessException.aiSchema("alternateSources 数组长度非法");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values) {
            result.add(source(value, "alternateSources"));
        }
        return result;
    }

    private Map<String, Object> source(Object raw, String field) {
        if (!(raw instanceof Map<?, ?>)) {
            throw BusinessException.aiSchema(field + " 缺失或结构非法");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) raw;
        String platform = requiredText(value, "platform", 16);
        if (!PLATFORMS.contains(platform)) {
            throw BusinessException.aiSchema(field + " 平台非法");
        }
        String path = requiredText(value, "path", 300);
        if (!validRepositoryPath(path, platform)) {
            throw BusinessException.aiSchema(field + " 仓库路径非法");
        }
        String url = requiredText(value, "url", 2048);
        if (!repositoryUrlMatches(url, platform, path, false)) {
            throw BusinessException.aiSchema(field + " 链接与平台、仓库路径不一致");
        }
        String license = requiredText(value, "license", 64);
        String readmeUrl = optionalText(value, "readmeUrl", 2048);
        if (readmeUrl != null && !repositoryUrlMatches(readmeUrl, platform, path, true)) {
            throw BusinessException.aiSchema(field + " README 链接与平台、仓库路径不一致");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("platform", platform);
        result.put("path", path);
        result.put("url", url);
        result.put("license", license);
        result.put("readmeUrl", readmeUrl);
        return result;
    }

    private boolean validRepositoryPath(String path, String platform) {
        String[] parts = path.split("/", -1);
        if (parts.length < 2 || (!"GITCODE".equals(platform) && parts.length != 2)) {
            return false;
        }
        for (String part : parts) {
            if (!part.matches("(?U)[\\w.-]+") || ".".equals(part) || "..".equals(part)) {
                return false;
            }
        }
        return true;
    }

    private boolean repositoryUrlMatches(String url, String platform, String path, boolean readme) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null) {
                return false;
            }
            if (uri.getPort() != -1 && uri.getPort() != 443) {
                return false;
            }
            if (!PLATFORM_HOSTS.get(platform).equalsIgnoreCase(uri.getHost())) {
                return false;
            }
            String actual = normalizePath(uri.getPath());
            String expected = normalizePath(path);
            return readme ? actual.equals(expected) || actual.startsWith(expected + "/") : actual.equals(expected);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private String normalizePath(String value) {
        String path = value == null ? "" : value.strip();
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.toLowerCase(Locale.ROOT).endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        return path.toLowerCase(Locale.ROOT);
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

    private String optionalText(Map<String, Object> value, String field, int max) {
        Object raw = value.get(field);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String text) || text.length() > max) {
            throw BusinessException.aiSchema(field + " 长度非法");
        }
        String stripped = text.strip();
        return stripped.isEmpty() ? null : stripped;
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
