package com.aijob.backend.project;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
import com.aijob.backend.job.JobMapper;
import com.aijob.backend.job.JobService;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.task.IdempotencyService;
import com.aijob.backend.task.TaskService;

@Service
public class ProjectService {

    private static final Set<String> DIFFICULTIES = Set.of("BEGINNER", "INTERMEDIATE", "ADVANCED");
    private static final Set<String> TASK_STATUSES = Set.of("PENDING", "IN_PROGRESS", "COMPLETED");

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
        String difficulty = Data.text(body, "difficulty", false, 16);
        difficulty = difficulty == null ? "INTERMEDIATE" : difficulty;
        if (!DIFFICULTIES.contains(difficulty)) {
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
                taskId -> mapper.insertRecommendation(recommendationId, userId, finalMatchItemId, taskId, profileVersion));
    }

    public Map<String, Object> recommendation(String userId, String recommendationId) {
        Map<String, Object> recommendation = mapper.recommendation(recommendationId, userId);
        if (recommendation == null) {
            throw BusinessException.notFound();
        }
        Map<String, Object> result = view.map(recommendation, "user_id", "items_json");
        List<Map<String, Object>> items = mapper.recommendationItems(recommendationId).stream()
                .map(this::recommendationItemVo)
                .sorted(Comparator.comparing((Map<String, Object> item) ->
                                !"GITHUB_REPOSITORY".equals(item.get("sourceType")))
                        .thenComparing(Comparator.comparingInt(this::recommendationCoverage).reversed())
                        .thenComparing(Comparator.comparingLong(this::recommendationStars).reversed()))
                .toList();
        result.put("items", items);
        Object snapshot = json.convert(json.parse((String) recommendation.get("items_json")), Object.class);
        List<String> targets = snapshot instanceof Map<?, ?> saved
                && saved.get("targetSkillCodes") instanceof List<?> codes
                ? codes.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of();
        for (Map<String, Object> item : items) {
            List<?> skills = item.get("skillCodes") instanceof List<?> values ? values : List.of();
            item.put("missingSkillCodes", targets.stream().filter(code -> !skills.contains(code)).toList());
        }
        List<String> covered = targets.stream().filter(code -> items.stream()
                .anyMatch(item -> item.get("skillCodes") instanceof List<?> skills && skills.contains(code))).toList();
        List<String> githubCovered = targets.stream().filter(code -> items.stream()
                .anyMatch(item -> "GITHUB_REPOSITORY".equals(item.get("sourceType"))
                        && item.get("skillCodes") instanceof List<?> skills && skills.contains(code))).toList();
        result.put("targetSkillCodes", targets);
        result.put("coveredSkillCodes", covered);
        result.put("githubCoveredSkillCodes", githubCovered);
        result.put("missingSkillCodes", targets.stream().filter(code -> !covered.contains(code)).toList());
        return result;
    }

    private int recommendationCoverage(Map<String, Object> item) {
        return item.get("skillCodes") instanceof List<?> skills ? skills.size() : 0;
    }

    private long recommendationStars(Map<String, Object> item) {
        if (item.get("repository") instanceof Map<?, ?> repository
                && repository.get("stargazersCount") instanceof Number stars) {
            return stars.longValue();
        }
        return 0;
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
        Map<String, Object> payload = Map.of("recommendation", recommendationInput(item), "weeklyHours", weeklyHours);
        String projectId = UUID.randomUUID().toString();
        return tasks.create(userId, "POST", route, key, body, "PROJECT_PLAN", "PROJECT", projectId,
                Map.of("profileVersion", profileVersion), payload,
                taskId -> mapper.insertProject(projectId, userId, recommendationItemId, taskId, weeklyHours,
                        String.valueOf(item.get("skill_codes_json"))));
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
        result.put("deliverables", json.convert(json.parse(String.valueOf(item.get("deliverables_json"))), Object.class));
        result.put("sourceType", item.get("source_type"));
        result.put("source", item.get("recommendation_source"));
        Object repository = item.get("repository_json");
        result.put("repository", repository == null ? null
                : json.convert(json.parse(String.valueOf(repository)), Object.class));
        return result;
    }

    private Map<String, Object> recommendationItemVo(Map<String, Object> item) {
        Map<String, Object> result = view.map(item, "user_id", "recommendation_id", "repository_json",
                "recommendation_source");
        result.put("source", item.get("recommendation_source"));
        Object repository = item.get("repository_json");
        result.put("repository", repository == null ? null
                : json.convert(json.parse(String.valueOf(repository)), Object.class));
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
