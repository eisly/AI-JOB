package com.aijob.backend.learning;

import java.util.ArrayList;
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
import com.aijob.backend.task.AiTask;
import com.aijob.backend.task.TaskMapper;
import com.aijob.backend.task.TaskService;

@Service
public class LearningService {

    private final LearningMapper mapper;
    private final JobMapper jobs;
    private final JobService jobService;
    private final ProfileService profiles;
    private final TaskService tasks;
    private final TaskMapper taskMapper;
    private final JsonService json;
    private final ApiView view;

    public LearningService(LearningMapper mapper, JobMapper jobs, JobService jobService, ProfileService profiles,
                           TaskService tasks, TaskMapper taskMapper, JsonService json, ApiView view) {
        this.mapper = mapper;
        this.jobs = jobs;
        this.jobService = jobService;
        this.profiles = profiles;
        this.tasks = tasks;
        this.taskMapper = taskMapper;
        this.json = json;
        this.view = view;
    }

    public ResponseEntity<CommonResult<Object>> createPlan(String userId, String route, String key,
                                                           Map<String, Object> body) {
        ResponseEntity<CommonResult<Object>> replay = tasks.replay(userId, "POST", route, key, body);
        if (replay != null) {
            return replay;
        }
        String matchItemId = Data.uuid(body, "matchItemId", true);
        int dailyMinutes = requiredInt(body, "dailyMinutes", 10, 120);
        int days = requiredInt(body, "days", 1, 14);
        Map<String, Object> match = jobs.matchItem(matchItemId, userId);
        if (match == null || !"READY".equals(String.valueOf(match.get("batch_status")))
                || "FAIL".equals(String.valueOf(match.get("eligibility_status")))) {
            throw BusinessException.notFound();
        }
        long profileVersion = ((Number) profiles.get(userId).get("profileVersion")).longValue();
        if (profileVersion != ((Number) match.get("profile_version")).longValue()
                || jobs.staleItems(String.valueOf(match.get("batch_id"))) > 0) {
            throw BusinessException.stale("匹配输入已过期，请重新匹配");
        }
        Map<String, Object> matchItem = matchItemInput(jobService.matchItemVo(match));
        List<String> allowedSkills = allowedSkills(matchItem);
        Map<String, Object> payload = Map.of("matchItem", matchItem, "profileVersion", profileVersion,
                "dailyMinutes", dailyMinutes, "days", days, "allowedSkillCodes", allowedSkills,
                "knowledgeRefs", List.of());
        String planId = UUID.randomUUID().toString();
        return tasks.create(userId, "POST", route, key, body, "LEARNING_PLAN", "LEARNING_PLAN", planId,
                Map.of("profileVersion", profileVersion), payload,
                taskId -> mapper.insertPlan(planId, userId, matchItemId, taskId, profileVersion, dailyMinutes, days));
    }

    public Map<String, Object> plan(String userId, String planId) {
        Map<String, Object> plan = mapper.plan(planId, userId);
        if (plan == null) {
            throw BusinessException.notFound();
        }
        return planVo(plan);
    }

    public PageResult<Map<String, Object>> plans(String userId, Integer current, Integer size) {
        int page = Data.page(current);
        int pageSize = Data.size(size);
        List<Map<String, Object>> records = mapper.planPage(userId, pageSize, Data.offset(current, size))
                .stream().map(this::planVo).toList();
        return new PageResult<>(mapper.planCount(userId), records, page, pageSize);
    }

    @Transactional
    public ResponseEntity<CommonResult<Object>> createSession(String userId, String route, String key,
                                                              Map<String, Object> body) {
        ResponseEntity<CommonResult<Object>> replay = tasks.replay(userId, "POST", route, key, body);
        if (replay != null) {
            return replay;
        }
        String planId = Data.uuid(body, "planId", true);
        String itemId = Data.uuid(body, "planItemId", true);
        Map<String, Object> item = mapper.lockItem(planId, itemId, userId);
        if (item == null || !"PRACTICE".equals(String.valueOf(item.get("kind")))) {
            throw BusinessException.notFound();
        }
        ensureNoActive(userId, itemId);
        String sessionId = UUID.randomUUID().toString();
        long profileVersion = ((Number) profiles.get(userId).get("profileVersion")).longValue();
        List<Map<String, Object>> candidates = candidates(String.valueOf(item.get("skill_code")), null);
        Map<String, Object> payload = questionPayload(sessionId, String.valueOf(item.get("skill_code")), candidates,
                mapper.usedQuestionIds(userId), "SELECT_OR_GENERATE");
        Map<String, Object> versions = Map.of("profileVersion", profileVersion);
        return tasks.create(userId, "POST", route, key, body, "LEARNING_QUESTION", "LEARNING_SESSION", sessionId,
                versions, payload, taskId -> {
                    mapper.insertSession(sessionId, userId, planId, itemId, null, null);
                    mapper.updateItemStatus(itemId, "IN_PROGRESS");
                });
    }

    public Map<String, Object> session(String userId, String sessionId) {
        Map<String, Object> session = mapper.session(sessionId, userId);
        if (session == null) {
            throw BusinessException.notFound();
        }
        return sessionVo(session);
    }

    @Transactional
    public ResponseEntity<CommonResult<Object>> answer(String userId, String sessionId, String route, String key,
                                                       Map<String, Object> body) {
        ResponseEntity<CommonResult<Object>> replay = tasks.replay(userId, "POST", route, key, body);
        if (replay != null) {
            return replay;
        }
        String content = Data.text(body, "content", true, 10000);
        int expected = requiredInt(body, "expectedVersion", 1, Integer.MAX_VALUE);
        Map<String, Object> session = mapper.lockSession(sessionId, userId);
        if (session == null) {
            throw BusinessException.notFound();
        }
        int actual = ((Number) session.get("version")).intValue();
        if (expected != actual) {
            throw BusinessException.versionConflict(actual);
        }
        Map<String, Object> existing = mapper.sessionAnswer(sessionId);
        if (existing != null) {
            String status = String.valueOf(existing.get("status"));
            if ("EVALUATING".equals(status)) {
                throw BusinessException.taskInProgress(String.valueOf(existing.get("task_id")));
            }
            if (!"FAILED".equals(status) || !content.equals(String.valueOf(existing.get("content")))
                    || !"ACTIVE".equals(String.valueOf(session.get("status")))) {
                throw BusinessException.conflict("回答已提交或失败重试内容不一致", "INVALID_STATE");
            }
            String answerId = String.valueOf(existing.get("id"));
            AiTask original = taskMapper.latestResourceTask("LEARNING_ANSWER", answerId);
            if (original == null) {
                throw BusinessException.conflict("原评价任务不存在", "INVALID_STATE");
            }
            return tasks.create(userId, "POST", route, key, body, "LEARNING_EVALUATE", "LEARNING_ANSWER", answerId,
                    json.map(original.getInputVersionsJson()), json.map(original.getInputSnapshotJson()), original.getId(),
                    taskId -> {
                        mapper.updateAnswerTask(answerId, "EVALUATING", taskId);
                        mapper.answerSubmitted(sessionId, answerId);
                    });
        }
        if (!"ACTIVE".equals(String.valueOf(session.get("status")))) {
            throw BusinessException.conflict("当前训练会话不能提交回答", "INVALID_STATE");
        }
        Map<String, Object> question = mapper.question(String.valueOf(session.get("question_id")));
        if (question == null) {
            throw BusinessException.conflict("训练题目尚未就绪", "INVALID_STATE");
        }
        String answerId = UUID.randomUUID().toString();
        long profileVersion = ((Number) profiles.get(userId).get("profileVersion")).longValue();
        int questionVersion = ((Number) session.get("question_version")).intValue();
        Map<String, Object> payload = Map.of("answerId", answerId, "question", internalQuestion(question), "answer", content);
        Map<String, Object> versions = Map.of("profileVersion", profileVersion, "questionVersion", questionVersion);
        return tasks.create(userId, "POST", route, key, body, "LEARNING_EVALUATE", "LEARNING_ANSWER", answerId,
                versions, payload, taskId -> {
                    mapper.insertAnswer(answerId, sessionId, userId, content, json.sha256(content), taskId);
                    mapper.answerSubmitted(sessionId, answerId);
                });
    }

    public Map<String, Object> answerDetail(String userId, String answerId) {
        Map<String, Object> answer = mapper.answer(answerId, userId);
        if (answer == null) {
            throw BusinessException.notFound();
        }
        return answerVo(answer);
    }

    public PageResult<Map<String, Object>> mistakes(String userId, Integer current, Integer size, Boolean resolved) {
        int page = Data.page(current);
        int pageSize = Data.size(size);
        Boolean filter = resolved == null ? Boolean.FALSE : resolved;
        List<Map<String, Object>> records = mapper.mistakes(userId, filter, pageSize, Data.offset(current, size))
                .stream().map(this::mistakeVo).toList();
        return new PageResult<>(mapper.mistakeCount(userId, filter), records, page, pageSize);
    }

    public Map<String, Object> mistake(String userId, String mistakeId) {
        Map<String, Object> mistake = mapper.mistake(mistakeId, userId);
        if (mistake == null) {
            throw BusinessException.notFound();
        }
        return mistakeVo(mistake);
    }

    @Transactional
    public ResponseEntity<CommonResult<Object>> retryMistake(String userId, String mistakeId, String route, String key,
                                                             Map<String, Object> body) {
        ResponseEntity<CommonResult<Object>> replay = tasks.replay(userId, "POST", route, key, body);
        if (replay != null) {
            return replay;
        }
        Map<String, Object> mistake = mapper.mistake(mistakeId, userId);
        if (mistake == null) {
            throw BusinessException.notFound();
        }
        Map<String, Object> answer = mapper.answer(String.valueOf(mistake.get("last_answer_id")), userId);
        Map<String, Object> session = mapper.session(String.valueOf(answer.get("session_id")), userId);
        ensureNoActive(userId, String.valueOf(session.get("plan_item_id")));
        Map<String, Object> question = mapper.question(String.valueOf(mistake.get("question_id")));
        if (question == null) {
            throw BusinessException.conflict("原题不可用", "INVALID_STATE");
        }
        String sessionId = UUID.randomUUID().toString();
        long profileVersion = ((Number) profiles.get(userId).get("profileVersion")).longValue();
        int questionVersion = ((Number) question.get("question_version")).intValue();
        List<Map<String, Object>> candidates = List.of(candidate(question));
        Map<String, Object> payload = questionPayload(sessionId,
                ((List<String>) json.convert(json.parse(String.valueOf(question.get("skill_codes_json"))), List.class)).get(0),
                candidates, List.of(), "REUSE_ONLY");
        Map<String, Object> versions = Map.of("profileVersion", profileVersion, "questionVersion", questionVersion);
        return tasks.create(userId, "POST", route, key, body, "LEARNING_QUESTION", "LEARNING_SESSION", sessionId,
                versions, payload, taskId -> mapper.insertSession(sessionId, userId,
                        String.valueOf(session.get("plan_id")), String.valueOf(session.get("plan_item_id")),
                        String.valueOf(question.get("id")), questionVersion));
    }

    @Transactional
    public boolean completeReading(String userId, String planId, String itemId) {
        Map<String, Object> item = mapper.item(planId, itemId, userId);
        if (item == null) {
            throw BusinessException.notFound();
        }
        if (!"READING".equals(String.valueOf(item.get("kind")))) {
            throw BusinessException.conflict("仅 READING 计划项允许手动完成", "INVALID_STATE");
        }
        mapper.updateItemStatus(itemId, "COMPLETED");
        mapper.completePlan(planId);
        return true;
    }

    public void completePractice(String planId, String itemId) {
        mapper.updateItemStatus(itemId, "COMPLETED");
        mapper.completePlan(planId);
    }

    public Map<String, Object> planVo(Map<String, Object> plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", plan.get("id"));
        result.put("matchItemId", plan.get("match_item_id"));
        result.put("profileVersion", plan.get("profile_version"));
        result.put("status", plan.get("status"));
        result.put("title", plan.get("title"));
        result.put("items", mapper.items(String.valueOf(plan.get("id"))).stream().map(view::map).toList());
        return result;
    }

    public Map<String, Object> sessionVo(Map<String, Object> session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", session.get("id"));
        result.put("planId", session.get("plan_id"));
        result.put("planItemId", session.get("plan_item_id"));
        result.put("status", session.get("status"));
        Object questionId = session.get("question_id");
        result.put("question", questionId == null || "PREPARING".equals(String.valueOf(session.get("status"))) ? null
                : questionVo(mapper.question(String.valueOf(questionId))));
        result.put("lastAnswerId", session.get("last_answer_id"));
        result.put("version", session.get("version"));
        return result;
    }

    public Map<String, Object> questionVo(Map<String, Object> question) {
        if (question == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", question.get("id"));
        result.put("skillCodes", json.convert(json.parse(String.valueOf(question.get("skill_codes_json"))), Object.class));
        result.put("difficulty", question.get("difficulty"));
        result.put("type", question.get("type"));
        result.put("stem", question.get("stem"));
        result.put("sourceType", question.get("source_type"));
        result.put("sourceTitle", null);
        result.put("questionVersion", question.get("question_version"));
        return result;
    }

    public Map<String, Object> internalQuestion(Map<String, Object> question) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", question.get("id"));
        result.put("questionVersion", question.get("question_version"));
        result.put("stem", question.get("stem"));
        result.put("skillCodes", json.convert(json.parse(String.valueOf(question.get("skill_codes_json"))), Object.class));
        result.put("referenceAnswer", question.get("reference_answer"));
        result.put("rubric", json.convert(json.parse(String.valueOf(question.get("rubric_json"))), Object.class));
        return result;
    }

    public Map<String, Object> candidate(Map<String, Object> question) {
        Map<String, Object> result = internalQuestion(question);
        result.put("version", result.remove("questionVersion"));
        result.put("difficulty", question.get("difficulty"));
        result.put("type", question.get("type"));
        return result;
    }

    public Map<String, Object> answerVo(Map<String, Object> answer) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", answer.get("id"));
        result.put("sessionId", answer.get("session_id"));
        result.put("content", answer.get("content"));
        result.put("status", answer.get("status"));
        result.put("taskId", answer.get("task_id"));
        result.put("evaluation", answer.get("evaluation_json") == null ? null
                : json.convert(json.parse(String.valueOf(answer.get("evaluation_json"))), Object.class));
        return result;
    }

    private Map<String, Object> mistakeVo(Map<String, Object> mistake) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", mistake.get("id"));
        result.put("question", questionVo(mapper.question(String.valueOf(mistake.get("question_id")))));
        result.put("lastAnswerId", mistake.get("last_answer_id"));
        result.put("wrongCount", mistake.get("wrong_count"));
        result.put("resolved", mistake.get("resolved"));
        return result;
    }

    private Map<String, Object> questionPayload(String sessionId, String skillCode, List<Map<String, Object>> candidates,
                                                 List<String> excluded, String mode) {
        return Map.of("sessionId", sessionId, "planItem", Map.of("skillCode", skillCode, "difficulty", "MEDIUM"),
                "candidateQuestions", candidates, "excludedQuestionIds", excluded,
                "knowledgeRefs", List.of(), "mode", mode);
    }

    private List<Map<String, Object>> candidates(String skillCode, String onlyQuestionId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> question : mapper.questionCandidates()) {
            List<String> skills = json.convert(json.parse(String.valueOf(question.get("skill_codes_json"))), List.class);
            if (skills.contains(skillCode) && (onlyQuestionId == null || onlyQuestionId.equals(String.valueOf(question.get("id"))))) {
                result.add(candidate(question));
            }
        }
        return result;
    }

    private void ensureNoActive(String userId, String itemId) {
        Map<String, Object> active = mapper.activeSession(userId, itemId);
        if (active != null) {
            throw BusinessException.conflict("当前计划项已有活动训练会话: " + active.get("id"), "INVALID_STATE");
        }
    }

    private Map<String, Object> matchItemInput(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("id", "jobId", "jobVersion", "title", "eligibility", "matchScore", "scoreVersion",
                "components", "gaps", "reasons", "source", "sourceUrl", "verifiedAt")) {
            result.put(key, source.get(key));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<String> allowedSkills(Map<String, Object> matchItem) {
        List<String> skills = new ArrayList<>();
        for (Map<String, Object> gap : (List<Map<String, Object>>) matchItem.getOrDefault("gaps", List.of())) {
            String code = String.valueOf(gap.get("skillCode"));
            if (!skills.contains(code)) skills.add(code);
        }
        if (skills.isEmpty()) {
            profiles.agentSkillCatalog().stream().limit(20).forEach(item -> skills.add(String.valueOf(item.get("code"))));
        }
        return skills;
    }

    private int requiredInt(Map<String, Object> body, String field, int min, int max) {
        Integer value = Data.integer(body, field, null, min, max);
        if (value == null) throw BusinessException.validation(field, field + " 不能为空");
        return value;
    }
}