package com.aijob.backend.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.CommonResult;
import com.aijob.backend.common.Data;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.common.PageResult;
import com.aijob.backend.common.Responses;
import com.aijob.backend.job.JobMapper;
import com.aijob.backend.job.JobService;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.task.IdempotencyService;
import com.aijob.backend.task.TaskService;

@Service
public class ProjectService {

    private static final Set<String> DIFFICULTIES = Set.of("BEGINNER", "INTERMEDIATE", "ADVANCED");
    private static final Set<String> TASK_STATUSES = Set.of("PENDING", "IN_PROGRESS", "COMPLETED");
    private static final Set<String> REPO_PLATFORMS = Set.of("GITHUB", "GITEE", "GITCODE");

    private final ProjectMapper mapper;
    private final JobMapper jobs;
    private final JobService jobService;
    private final ProfileService profiles;
    private final TaskService tasks;
    private final IdempotencyService idempotency;
    private final JsonService json;
    private final ApiView view;
    private final ProjectProgressService progressService;

    public ProjectService(ProjectMapper mapper, JobMapper jobs, JobService jobService, ProfileService profiles,
                          TaskService tasks, IdempotencyService idempotency, JsonService json, ApiView view,
                          ProjectProgressService progressService) {
        this.mapper = mapper;
        this.jobs = jobs;
        this.jobService = jobService;
        this.profiles = profiles;
        this.tasks = tasks;
        this.idempotency = idempotency;
        this.json = json;
        this.view = view;
        this.progressService = progressService;
    }

    public ResponseEntity<CommonResult<Object>> recommend(String userId, String route, String key,
                                                           Map<String, Object> body) {
        ResponseEntity<CommonResult<Object>> replay = tasks.replay(userId, "POST", route, key, body);
        if (replay != null) {
            return replay;
        }
        String matchItemId = Data.uuid(body, "matchItemId", false);
        Map<String, Object> match = null;
        Map<String, Object> matchItem = null;
        if (matchItemId != null) {
            match = jobs.matchItem(matchItemId, userId);
            if (match == null) {
                throw BusinessException.notFound("岗位匹配条目不存在或不属于当前用户",
                        matchItemDetails("NOT_FOUND_OR_NOT_OWNED",
                                "请从“可选匹配结果”中重新选择本人有效条目，或留空后按技能生成"));
            }
            if (!"READY".equals(String.valueOf(match.get("batch_status")))) {
                throw BusinessException.conflict("匹配结果尚未生成完成，暂不能用于项目推荐", "INVALID_STATE",
                        matchItemDetails("NOT_READY", "请等待匹配完成，或重新运行岗位匹配后再选择"));
            }
            if ("FAIL".equals(String.valueOf(match.get("eligibility_status")))) {
                throw BusinessException.validation("matchItemId",
                        "该匹配条目未通过岗位硬性条件，不能用于项目推荐；请选择其他条目或留空");
            }
            long currentProfileVersion = ((Number) profiles.get(userId).get("profileVersion")).longValue();
            boolean profileChanged = currentProfileVersion != ((Number) match.get("profile_version")).longValue();
            boolean expired = match.get("batch_created_at") instanceof java.time.LocalDateTime created
                    && created.isBefore(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(90));
            if (profileChanged || expired || jobs.staleItems(String.valueOf(match.get("batch_id"))) > 0) {
                throw BusinessException.stale("匹配结果已过期，请重新运行岗位匹配后再选择",
                        matchItemDetails("STALE_MATCH", "画像、岗位版本或来源核验状态已变化；请到岗位匹配重新匹配"));
            }
            int matchVersion = ((Number) match.get("job_version")).intValue();
            Map<String, Object> job = jobs.job(String.valueOf(match.get("job_id")), userId);
            if (job == null || !"ACTIVE".equals(String.valueOf(job.get("status")))
                    || ((Number) job.get("job_version")).intValue() != matchVersion) {
                throw BusinessException.stale("匹配岗位已关闭或版本已变化，请重新匹配",
                        matchItemDetails("JOB_CHANGED", "请重新运行岗位匹配，选择最新有效条目"));
            }
            matchItem = matchItemInput(jobService.matchItemVo(match));
        }
        String query = Data.text(body, "query", false, 120);
        List<String> sources = repositorySources(body);
        List<String> techStackKeywords = techStackKeywords(body);
        String difficulty = Data.text(body, "difficulty", false, 16);
        if (difficulty != null && !DIFFICULTIES.contains(difficulty)) {
            throw BusinessException.validation("difficulty", "项目难度非法");
        }
        int maxDurationWeeks = Data.integer(body, "maxDurationWeeks", 12, 1, 12);
        List<String> allowedSkills = allowedSkills(userId, body, matchItem);
        Map<String, Object> profile = profiles.get(userId);
        long profileVersion = ((Number) profile.get("profileVersion")).longValue();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("profile", profile);
        payload.put("allowedSkillCodes", allowedSkills);
        payload.put("matchItem", matchItem);
        payload.put("query", query);
        payload.put("sources", sources);
        payload.put("techStackKeywords", techStackKeywords);
        if (!techStackKeywords.isEmpty()) {
            payload.put("discoveryRound", 0);
        }
        payload.put("difficulty", difficulty);
        payload.put("maxDurationWeeks", maxDurationWeeks);
        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("profileVersion", profileVersion);
        if (matchItem != null) {
            versions.put("jobVersion", matchItem.get("jobVersion"));
        }
        String recommendationId = UUID.randomUUID().toString();
        String finalMatchItemId = matchItemId;
        return tasks.create(userId, "POST", route, key, body, "PROJECT_RECOMMEND", "PROJECT_RECOMMENDATION",
                recommendationId, versions, payload,
                taskId -> {
                    mapper.insertRecommendation(recommendationId, userId, finalMatchItemId, taskId, profileVersion, "REPOSITORY");
                    if (!techStackKeywords.isEmpty()) {
                        mapper.setDiscoveryBatch(recommendationId, null, null, 0);
                    }
                });
    }

    @Transactional
    public ResponseEntity<CommonResult<Object>> more(String userId, String rootId, String route, String key,
                                                       Map<String, Object> body) {
        String previousId = Data.uuid(body, "afterBatchId", true);
        Map<String, Object> root = mapper.lockRecommendation(rootId, userId);
        if (root == null) {
            throw BusinessException.notFound();
        }
        ResponseEntity<CommonResult<Object>> replay = tasks.replay(userId, "POST", route, key, body);
        if (replay != null) {
            return replay;
        }
        Map<String, Object> previous = previousId.equals(rootId) ? root : mapper.lockRecommendation(previousId, userId);
        if (previous == null || (!previousId.equals(rootId) && !rootId.equals(previous.get("root_recommendation_id")))) {
            throw BusinessException.notFound();
        }
        String existing = mapper.continuationTask(previousId, userId);
        if (existing != null) {
            return Responses.acceptedEntity(tasks.detail(userId, existing));
        }
        if (!"READY".equals(previous.get("status")) || !canContinue(previous)) {
            throw BusinessException.conflict("当前推荐没有可继续检索的下一批", "INVALID_STATE");
        }
        int round = ((Number) previous.get("discovery_round")).intValue() + 1;
        long profileVersion = ((Number) root.get("profile_version")).longValue();
        if (profileVersion != ((Number) profiles.get(userId).get("profileVersion")).longValue()) {
            throw BusinessException.stale("求职画像已更新，请重新生成项目推荐", Map.of());
        }
        Map<String, Object> input = mapper.discoveryInput(String.valueOf(root.get("task_id")), userId);
        if (input == null) {
            throw BusinessException.conflict("找不到原推荐条件，请重新生成项目推荐", "INVALID_STATE");
        }
        Map<String, Object> payload = new LinkedHashMap<>(json.map(String.valueOf(input.get("input_snapshot_json"))));
        Map<String, Object> versions = json.map(String.valueOf(input.get("input_versions_json")));
        Set<Object> seen = new LinkedHashSet<>();
        List<Map<String, Object>> batches = new ArrayList<>();
        batches.add(root);
        batches.addAll(mapper.discoveryBatches(rootId, userId));
        for (Map<String, Object> batch : batches) {
            if (searchState(batch).get("keys") instanceof List<?> keys) {
                seen.addAll(keys);
            }
        }
        payload.put("discoveryRound", round);
        payload.put("searchCursor", searchState(previous).get("cursor"));
        payload.put("seenRepositoryKeys", List.copyOf(seen));
        String id = UUID.randomUUID().toString();
        return tasks.create(userId, "POST", route, key, body, "PROJECT_RECOMMEND", "PROJECT_RECOMMENDATION",
                id, versions, payload, taskId -> {
                    mapper.insertRecommendation(id, userId, (String) root.get("match_item_id"), taskId, profileVersion, "REPOSITORY");
                    mapper.setDiscoveryBatch(id, rootId, previousId, round);
                });
    }

    public Map<String, Object> recommendation(String userId, String recommendationId) {
        Map<String, Object> recommendation = mapper.recommendation(recommendationId, userId);
        if (recommendation == null) {
            throw BusinessException.notFound();
        }
        if (recommendation.get("root_recommendation_id") instanceof String root) {
            return recommendation(userId, root);
        }
        Map<String, Object> result = view.map(recommendation, "user_id", "items_json", "search_state_json",
                "previous_batch_id", "root_recommendation_id", "discovery_round");
        if (result.get("mode") == null) {
            result.put("mode", "LEGACY_IDEA");
        }
        if (result.get("sourceRuns") == null) {
            result.put("sourceRuns", List.of());
        }
        if (recommendation.get("discovery_round") != null && "READY".equals(recommendation.get("status"))) {
            List<Map<String, Object>> batches = new ArrayList<>();
            batches.add(recommendation);
            batches.addAll(mapper.discoveryBatches(recommendationId, userId));
            List<Object> items = new ArrayList<>();
            List<Object> documented = new ArrayList<>();
            Map<String, Object> runs = new LinkedHashMap<>();
            Map<String, Object> lastReady = recommendation;
            for (Map<String, Object> batch : batches) {
                if (!"READY".equals(batch.get("status"))) {
                    result.put("continuationTaskId", batch.get("task_id"));
                    break;
                }
                lastReady = batch;
                if (jsonValue(batch.get("items_json")) instanceof List<?> page) {
                    for (Object item : page) {
                        if (item instanceof Map<?, ?> row && row.get("stackEvidence") instanceof List<?> evidence
                                && evidence.stream().anyMatch(entry -> entry instanceof Map<?, ?> fact
                                    && "DOCUMENTED".equals(fact.get("status")))) {
                            documented.add(item);
                        } else {
                            items.add(item);
                        }
                    }
                }
                if (jsonValue(batch.get("source_runs_json")) instanceof List<?> sources) {
                    for (Object raw : sources) {
                        if (raw instanceof Map<?, ?> run) {
                            runs.put(String.valueOf(run.get("platform")), run);
                        }
                    }
                }
            }
            items.addAll(documented);
            result.put("items", items);
            result.put("sourceRuns", List.copyOf(runs.values()));
            result.put("afterBatchId", lastReady.get("id"));
            result.put("hasMore", canContinue(lastReady));
            result.put("searchLimitReached", ((Number) lastReady.get("discovery_round")).intValue() >= 10
                    && hasCursor(lastReady));
            return result;
        }
        result.put("items", mapper.recommendationItems(recommendationId).stream()
                .map(item -> view.map(item, "user_id", "recommendation_id")).toList());
        return result;
    }

    private Map<String, Object> searchState(Map<String, Object> batch) {
        Object value = batch.get("search_state_json");
        return value == null ? Map.of() : json.map(String.valueOf(value));
    }

    private boolean hasCursor(Map<String, Object> batch) {
        return searchState(batch).get("cursor") instanceof Map<?, ?> cursor
                && cursor.values().stream().anyMatch(value -> value instanceof Number count && count.intValue() != 0);
    }

    private boolean canContinue(Map<String, Object> batch) {
        return batch.get("discovery_round") instanceof Number round && round.intValue() < 10 && hasCursor(batch);
    }

    public ResponseEntity<CommonResult<Object>> createPlan(String userId, String route, String key,
                                                            Map<String, Object> body) {
        ResponseEntity<CommonResult<Object>> replay = tasks.replay(userId, "POST", route, key, body);
        if (replay != null) {
            return replay;
        }
        String recommendationItemId = Data.uuid(body, "recommendationItemId", true);
        Integer weeklyHours = Data.integer(body, "weeklyHours", null, 1, 40);
        if (weeklyHours == null) {
            throw BusinessException.validation("weeklyHours", "weeklyHours 不能为空");
        }
        Map<String, Object> item = mapper.recommendationItem(recommendationItemId, userId);
        if (item == null) {
            throw BusinessException.notFound();
        }
        if (!"READY".equals(String.valueOf(item.get("recommendation_status")))) {
            throw BusinessException.conflict("项目推荐尚未就绪", "INVALID_STATE");
        }
        long profileVersion = ((Number) item.get("profile_version")).longValue();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recommendation", recommendationInput(item));
        payload.put("weeklyHours", weeklyHours);
        String primarySourceJson = item.get("primary_source_json") == null
                ? null : String.valueOf(item.get("primary_source_json"));
        String projectId = UUID.randomUUID().toString();
        return tasks.create(userId, "POST", route, key, body, "PROJECT_PLAN", "PROJECT", projectId,
                Map.of("profileVersion", profileVersion), payload,
                taskId -> mapper.insertProject(projectId, userId, recommendationItemId, taskId, weeklyHours,
                        String.valueOf(item.get("skill_codes_json")), primarySourceJson));
    }

    public PageResult<Map<String, Object>> projects(String userId, Integer current, Integer size) {
        int page = Data.page(current);
        int pageSize = Data.size(size);
        List<Map<String, Object>> records = mapper.projectPage(userId, pageSize, Data.offset(current, size)).stream()
                .map(row -> projectVo(row, false)).toList();
        return new PageResult<>(mapper.projectCount(userId), records, page, pageSize);
    }

    public Map<String, Object> project(String userId, String projectId) {
        Map<String, Object> project = mapper.project(projectId, userId);
        if (project == null) {
            throw BusinessException.notFound();
        }
        return projectVo(project, true);
    }

    @Transactional
    public Map<String, Object> updateTask(String userId, String projectId, String taskId, Map<String, Object> body) {
        String status = Data.text(body, "status", true, 16);
        if (!TASK_STATUSES.contains(status)) {
            throw BusinessException.validation("status", "项目任务状态非法");
        }
        Integer expectedVersion = Data.integer(body, "expectedVersion", null, 1, Integer.MAX_VALUE);
        if (expectedVersion == null) {
            throw BusinessException.validation("expectedVersion", "expectedVersion 不能为空");
        }
        Map<String, Object> project = mapper.lockProject(projectId, userId);
        if (project == null) {
            throw BusinessException.notFound();
        }
        int actual = ((Number) project.get("version")).intValue();
        if (actual != expectedVersion) {
            throw BusinessException.versionConflict(actual);
        }
        if (!Set.of("ACTIVE", "COMPLETED").contains(String.valueOf(project.get("status")))) {
            throw BusinessException.conflict("项目计划尚未就绪", "INVALID_STATE");
        }
        if (mapper.lockTask(projectId, taskId, userId) == null) {
            throw BusinessException.notFound();
        }
        mapper.updateTaskStatus(projectId, taskId, status);
        Map<String, Object> counts = mapper.taskCounts(projectId);
        long total = ((Number) counts.get("total")).longValue();
        long completed = ((Number) counts.get("completed")).longValue();
        ProjectProgressService.Result progress = progressService.calculate(completed, total);
        if (mapper.updateProgress(projectId, userId, expectedVersion, progress.progress(), progress.status()) == 0) {
            throw BusinessException.versionConflict(actual);
        }
        return project(userId, projectId);
    }

    public ResponseEntity<CommonResult<Object>> addDeliverable(String userId, String projectId, String route,
                                                                String key, Map<String, Object> body) {
        String taskId = Data.uuid(body, "taskId", true);
        String title = Data.text(body, "title", true, 200);
        String url = Data.url(body, "url");
        String notes = Data.text(body, "notes", false, 10000);
        return idempotency.execute(userId, "POST", route, key, body, 200, () -> {
            Map<String, Object> project = mapper.lockProject(projectId, userId);
            if (project == null) {
                throw BusinessException.notFound();
            }
            if (mapper.task(projectId, taskId) == null) {
                throw BusinessException.notFound();
            }
            mapper.insertDeliverable(UUID.randomUUID().toString(), projectId, taskId, title, url, notes);
            return project(userId, projectId);
        });
    }

    private Map<String, Object> projectVo(Map<String, Object> project, boolean includeDetails) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", project.get("id"));
        result.put("recommendationItemId", project.get("recommendation_item_id"));
        result.put("taskId", project.get("task_id"));
        result.put("title", project.get("title"));
        result.put("summary", project.get("summary"));
        result.put("primarySource", jsonValue(project.get("primary_source_json")));
        result.put("weeklyHours", project.get("weekly_hours"));
        result.put("skillCodes", json.convert(json.parse(String.valueOf(project.get("skill_codes_json"))), Object.class));
        result.put("status", project.get("status"));
        result.put("progress", project.get("progress"));
        result.put("version", project.get("version"));
        result.put("createdAt", view.value(project.get("created_at")));
        result.put("updatedAt", view.value(project.get("updated_at")));
        if (includeDetails) {
            String projectId = String.valueOf(project.get("id"));
            List<Map<String, Object>> taskRows = mapper.tasks(projectId);
            List<Map<String, Object>> milestones = new ArrayList<>();
            for (Map<String, Object> milestone : mapper.milestones(projectId)) {
                Map<String, Object> item = view.map(milestone);
                String milestoneId = String.valueOf(milestone.get("id"));
                item.put("tasks", taskRows.stream()
                        .filter(task -> milestoneId.equals(String.valueOf(task.get("milestone_id"))))
                        .map(view::map).toList());
                milestones.add(item);
            }
            result.put("milestones", milestones);
            result.put("deliverables", mapper.deliverables(projectId).stream().map(view::map).toList());
        }
        return result;
    }

    private Map<String, Object> recommendationInput(Map<String, Object> item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", item.get("title"));
        result.put("summary", item.get("summary"));
        result.put("skillCodes", json.convert(json.parse(String.valueOf(item.get("skill_codes_json"))), Object.class));
        result.put("difficulty", item.get("difficulty"));
        result.put("estimatedWeeks", item.get("estimated_weeks"));
        result.put("reason", item.get("reason"));
        Object gettingStarted = jsonValue(item.get("getting_started_json"));
        result.put("gettingStarted", gettingStarted == null ? List.of() : gettingStarted);
        result.put("deliverables", json.convert(json.parse(String.valueOf(item.get("deliverables_json"))), Object.class));
        result.put("primarySource", jsonValue(item.get("primary_source_json")));
        Object alternateSources = jsonValue(item.get("alternate_sources_json"));
        result.put("alternateSources", alternateSources == null ? List.of() : alternateSources);
        return result;
    }

    private Object jsonValue(Object raw) {
        return raw == null ? null : json.convert(json.parse(String.valueOf(raw)), Object.class);
    }

    private List<String> repositorySources(Map<String, Object> body) {
        List<String> result = new ArrayList<>();
        for (Object raw : Data.list(body, "sources", false, 3)) {
            if (!(raw instanceof String value) || value.isBlank()) {
                throw BusinessException.validation("sources", "来源平台非法");
            }
            String platform = value.trim().toUpperCase(java.util.Locale.ROOT);
            if (!REPO_PLATFORMS.contains(platform)) {
                throw BusinessException.validation("sources", "来源平台仅支持 GITHUB、GITEE、GITCODE");
            }
            if (!result.contains(platform)) {
                result.add(platform);
            }
        }
        return result;
    }

    private List<String> techStackKeywords(Map<String, Object> body) {
        List<String> result = new ArrayList<>();
        for (Object raw : Data.list(body, "techStackKeywords", false, 5)) {
            if (!(raw instanceof String value)) {
                throw BusinessException.validation("techStackKeywords", "技术栈关键词必须为字符串");
            }
            String keyword = value.trim();
            if (keyword.isEmpty()) {
                throw BusinessException.validation("techStackKeywords", "技术栈关键词不能为空白");
            }
            if (keyword.length() > 64) {
                throw BusinessException.validation("techStackKeywords", "单个技术栈关键词不能超过 64 个字符");
            }
            String folded = keyword.toLowerCase(java.util.Locale.ROOT);
            boolean duplicated = result.stream()
                    .anyMatch(item -> item.toLowerCase(java.util.Locale.ROOT).equals(folded));
            if (!duplicated) {
                result.add(keyword);
            }
        }
        return result;
    }

    private Map<String, Object> matchItemDetails(String reason, String hint) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("field", "matchItemId");
        details.put("reason", reason);
        details.put("hint", hint);
        return details;
    }

    private Map<String, Object> matchItemInput(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("id", "jobId", "jobVersion", "title", "eligibility", "matchScore", "scoreVersion",
                "components", "gaps", "reasons", "source", "sourceUrl", "verifiedAt")) {
            result.put(field, source.get(field));
        }
        return result;
    }

    private List<String> allowedSkills(String userId, Map<String, Object> body, Map<String, Object> matchItem) {
        List<String> result = new ArrayList<>();
        for (Object raw : Data.list(body, "skillCodes", false, 5)) {
            if (!(raw instanceof String value) || value.isBlank() || value.length() > 64) {
                throw BusinessException.validation("skillCodes", "技能编码非法");
            }
            String code = value.trim();
            profiles.requireSkill(userId, code);
            if (!result.contains(code)) {
                result.add(code);
            }
        }
        if (result.isEmpty() && matchItem != null) {
            Object rawGaps = matchItem.get("gaps");
            if (rawGaps instanceof List<?> gaps) {
                for (Object raw : gaps) {
                    if (raw instanceof Map<?, ?> gap && gap.get("skillCode") != null) {
                        String code = String.valueOf(gap.get("skillCode"));
                        if (!result.contains(code) && result.size() < 20) {
                            result.add(code);
                        }
                    }
                }
            }
        }
        if (result.isEmpty()) {
            profiles.agentSkillCatalog(userId).stream().limit(20).forEach(item -> result.add(String.valueOf(item.get("code"))));
        }
        if (result.isEmpty()) {
            throw BusinessException.conflict("技能目录为空，无法生成项目推荐", "INVALID_STATE");
        }
        return result;
    }
}
