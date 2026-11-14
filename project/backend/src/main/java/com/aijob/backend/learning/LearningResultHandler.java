package com.aijob.backend.learning;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.aijob.backend.ability.AbilityService;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.task.AiTask;
import com.aijob.backend.task.TaskResultHandler;

import tools.jackson.databind.JsonNode;

@Component
public class LearningResultHandler implements TaskResultHandler {

    private final LearningMapper mapper;
    private final LearningService learning;
    private final EvaluationService evaluations;
    private final AbilityService ability;
    private final ProfileService profiles;
    private final JsonService json;

    public LearningResultHandler(LearningMapper mapper, LearningService learning, EvaluationService evaluations,
                                 AbilityService ability, ProfileService profiles, JsonService json) {
        this.mapper = mapper;
        this.learning = learning;
        this.evaluations = evaluations;
        this.ability = ability;
        this.profiles = profiles;
        this.json = json;
    }

    @Override
    public Set<String> operations() {
        return Set.of("LEARNING_PLAN", "LEARNING_QUESTION", "LEARNING_EVALUATE");
    }

    @Override
    public int success(AiTask task, JsonNode result) {
        return switch (task.getOperation()) {
            case "LEARNING_PLAN" -> plan(task, result);
            case "LEARNING_QUESTION" -> question(task, result);
            case "LEARNING_EVALUATE" -> evaluate(task, result);
            default -> throw BusinessException.aiSchema("不支持的训练任务");
        };
    }

    @Override
    public void failure(AiTask task, JsonNode error) {
        switch (task.getOperation()) {
            case "LEARNING_PLAN" -> {
                Map<String, Object> plan = mapper.plan(task.getResourceId(), task.getUserId());
                if (plan != null) mapper.updatePlan(task.getResourceId(), "FAILED", task.getId(), (String) plan.get("title"));
            }
            case "LEARNING_QUESTION" -> {
                if (mapper.session(task.getResourceId(), task.getUserId()) != null) {
                    mapper.updateSessionStatus(task.getResourceId(), "FAILED");
                }
            }
            case "LEARNING_EVALUATE" -> {
                Map<String, Object> answer = mapper.answer(task.getResourceId(), task.getUserId());
                if (answer != null) {
                    mapper.updateAnswerTask(task.getResourceId(), "FAILED", task.getId());
                    mapper.updateSessionStatus(String.valueOf(answer.get("session_id")), "ACTIVE");
                }
            }
            default -> throw BusinessException.aiSchema("不支持的训练任务");
        }
    }

    @Override
    public void prepareRetry(AiTask original, String newTaskId) {
        switch (original.getOperation()) {
            case "LEARNING_PLAN" -> {
                Map<String, Object> plan = mapper.plan(original.getResourceId(), original.getUserId());
                mapper.updatePlan(original.getResourceId(), "GENERATING", newTaskId, (String) plan.get("title"));
            }
            case "LEARNING_QUESTION" -> mapper.updateSessionStatus(original.getResourceId(), "PREPARING");
            case "LEARNING_EVALUATE" -> {
                Map<String, Object> answer = mapper.answer(original.getResourceId(), original.getUserId());
                mapper.updateAnswerTask(original.getResourceId(), "EVALUATING", newTaskId);
                mapper.answerSubmitted(String.valueOf(answer.get("session_id")), original.getResourceId());
            }
            default -> throw BusinessException.conflict("不支持的任务重试", "INVALID_STATE");
        }
    }

    @SuppressWarnings("unchecked")
    private int plan(AiTask task, JsonNode result) {
        Map<String, Object> plan = mapper.lockPlan(task.getResourceId(), task.getUserId());
        if (plan == null || !"GENERATING".equals(String.valueOf(plan.get("status")))) {
            throw BusinessException.conflict("学习计划状态已变化", "INVALID_STATE");
        }
        Map<String, Object> value = json.map(result);
        String title = requiredText(value, "title", 200);
        List<Map<String, Object>> items = (List<Map<String, Object>>) value.get("items");
        if (items == null || items.isEmpty() || items.size() > 20) {
            throw BusinessException.aiSchema("学习计划必须包含 1..20 项");
        }
        Map<String, Object> payload = json.map(task.getInputSnapshotJson());
        String planSource = payload.get("planSource") == null ? "MATCH" : String.valueOf(payload.get("planSource"));
        boolean strict = "RESUME".equals(planSource);
        Map<String, Object> resume = payload.get("resume") instanceof Map<?, ?> rawResume
                ? new LinkedHashMap<>((Map<String, Object>) rawResume) : Map.of();
        String resumeText = normalizeText(String.valueOf(resume.getOrDefault("projectsText", "")));
        List<String> allowed = (List<String>) payload.get("allowedSkillCodes");
        GithubFacts github = githubFacts(value.get("github"));
        int total = 0;
        List<Map<String, Object>> validated = new ArrayList<>();
        for (Map<String, Object> raw : items) {
            String skill = requiredText(raw, "skillCode", 64);
            if (!allowed.contains(skill)) {
                throw BusinessException.aiSchema("学习计划包含输入外技能");
            }
            String kind = requiredText(raw, "kind", 16);
            if (!Set.of("READING", "PRACTICE", "INTERVIEW").contains(kind)) {
                throw BusinessException.aiSchema("学习计划项类型非法");
            }
            String category = strict ? requiredText(raw, "category", 24) : optionalText(raw, "category", 24, "BASIC");
            if (!Set.of("BASIC", "RESUME_PROJECT", "GITHUB").contains(category)) {
                throw BusinessException.aiSchema("学习计划项分类非法");
            }
            int minutes = requiredInt(raw.get("estimatedMinutes"), 5, 120);
            total += minutes;
            List<Object> refs = (List<Object>) raw.getOrDefault("resourceRefs", List.of());
            if (!refs.isEmpty() && ((List<Object>) payload.get("knowledgeRefs")).isEmpty()) {
                throw BusinessException.aiSchema("学习计划包含未授权知识引用");
            }
            String evidenceStatus = strict ? requiredText(raw, "evidenceStatus", 16)
                    : optionalText(raw, "evidenceStatus", 16, "ASSESSED");
            if (!Set.of("ASSESSED", "PENDING").contains(evidenceStatus)) {
                throw BusinessException.aiSchema("计划项证据状态非法");
            }
            String evidenceQuote = optionalText(raw, "evidenceQuote", 500, null);
            List<Map<String, Object>> links = resourceLinks(raw.get("resourceLinks"));
            if ("RESUME_PROJECT".equals(category)) {
                if (!strict) {
                    throw BusinessException.aiSchema("仅基于简历的计划允许简历项目深挖");
                }
                if (evidenceQuote == null || normalizeText(evidenceQuote).length() < 6
                        || !resumeText.contains(normalizeText(evidenceQuote))) {
                    throw BusinessException.aiSchema("简历项目训练必须引用简历原文中的真实项目描述");
                }
                if ("PENDING".equals(evidenceStatus)) {
                    throw BusinessException.aiSchema("简历项目训练必须给出已评估证据");
                }
            }
            if ("GITHUB".equals(category)) {
                if (!"AVAILABLE".equals(github.status())) {
                    throw BusinessException.aiSchema("GitHub 数据不可用，不能生成 GitHub 学习项");
                }
                if (links.isEmpty() || links.size() > 3) {
                    throw BusinessException.aiSchema("GitHub 学习项必须包含 1..3 个链接");
                }
                for (Map<String, Object> link : links) {
                    if (!github.urls().contains(normalizeUrl(String.valueOf(link.get("url"))))) {
                        throw BusinessException.aiSchema("GitHub 学习项包含输入候选之外的仓库链接");
                    }
                }
                if (!"ASSESSED".equals(evidenceStatus)) {
                    throw BusinessException.aiSchema("GitHub 学习项必须为已评估证据");
                }
            }
            Map<String, Object> item = new LinkedHashMap<>(raw);
            item.put("category", category);
            item.put("objective", strict ? requiredText(raw, "objective", 500)
                    : optionalText(raw, "objective", 500, title));
            item.put("reason", strict ? requiredText(raw, "reason", 500)
                    : optionalText(raw, "reason", 500, "依据当前训练计划生成"));
            item.put("method", strict ? requiredText(raw, "method", 500)
                    : optionalText(raw, "method", 500, "按计划完成学习或练习"));
            item.put("completionCriteria", strict ? requiredText(raw, "completionCriteria", 500)
                    : optionalText(raw, "completionCriteria", 500, "完成该项训练并提交有效记录"));
            item.put("evidenceStatus", evidenceStatus);
            item.put("evidenceQuote", evidenceQuote);
            item.put("resourceLinks", links);
            item.put("resourceRefs", refs);
            validated.add(item);
        }
        if (total > ((Number) plan.get("daily_minutes")).intValue() * ((Number) plan.get("days")).intValue()) {
            throw BusinessException.aiSchema("学习计划总时长超过输入上限");
        }
        int seq = 1;
        for (Map<String, Object> item : validated) {
            mapper.insertItem(UUID.randomUUID().toString(), task.getResourceId(), seq++,
                    String.valueOf(item.get("skillCode")), requiredText(item, "title", 200),
                    String.valueOf(item.get("kind")), String.valueOf(item.get("category")),
                    String.valueOf(item.get("objective")), String.valueOf(item.get("reason")),
                    String.valueOf(item.get("method")), String.valueOf(item.get("completionCriteria")),
                    String.valueOf(item.get("evidenceStatus")), (String) item.get("evidenceQuote"),
                    json.stringify(item.get("resourceLinks")), ((Number) item.get("estimatedMinutes")).intValue(),
                    json.stringify(item.get("resourceRefs")));
        }
        mapper.updatePlan(task.getResourceId(), "ACTIVE", task.getId(), title);
        Map<String, Object> basis = plan.get("basis_json") == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(json.map(String.valueOf(plan.get("basis_json"))));
        Object summary = value.get("summary");
        if (summary != null) {
            basis.put("summary", String.valueOf(summary));
        }
        Map<String, Object> githubBasis = new LinkedHashMap<>();
        githubBasis.put("status", github.status());
        githubBasis.put("candidateCount", github.urls().size());
        githubBasis.put("fetchedAt", github.fetchedAt());
        githubBasis.put("reason", github.reason());
        basis.put("github", githubBasis);
        mapper.updatePlanBasis(task.getResourceId(), json.stringify(basis));
        return 1;
    }

    private GithubFacts githubFacts(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return new GithubFacts("UNAVAILABLE", Set.of(), null, "Agent 未返回 GitHub 数据状态");
        }
        Object rawStatus = map.get("status");
        String status = rawStatus == null ? "UNAVAILABLE" : String.valueOf(rawStatus);
        Set<String> urls = new HashSet<>();
        List<Map<String, Object>> candidates = map.get("candidates") instanceof List<?> list
                ? (List<Map<String, Object>>) list : List.of();
        if ("AVAILABLE".equals(status)) {
            for (Map<String, Object> candidate : candidates) {
                String url = candidate.get("htmlUrl") == null ? null : String.valueOf(candidate.get("htmlUrl"));
                if (url == null || url.isBlank()) {
                    throw BusinessException.aiSchema("GitHub 候选缺少仓库链接");
                }
                if (!"GITHUB_API".equals(String.valueOf(candidate.get("source")))) {
                    throw BusinessException.aiSchema("GitHub 候选来源不可信");
                }
                if (candidate.get("verifiedAt") == null) {
                    throw BusinessException.aiSchema("GitHub 候选缺少获取时间");
                }
                urls.add(normalizeUrl(url));
            }
            if (urls.isEmpty()) {
                return new GithubFacts("UNAVAILABLE", Set.of(), null, "GitHub 检索无可用候选");
            }
        }
        return new GithubFacts(status, urls, map.get("fetchedAt"), map.get("reason") == null ? null
                : String.valueOf(map.get("reason")));
    }

    private List<Map<String, Object>> resourceLinks(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> links = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                links.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return links;
    }

    private String normalizeUrl(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("https://")) normalized = normalized.substring(8);
        if (normalized.startsWith("http://")) normalized = normalized.substring(7);
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private String optionalText(Map<String, Object> value, String field, int max, String fallback) {
        Object raw = value.get(field);
        if (!(raw instanceof String text) || text.isBlank() || text.length() > max) {
            return fallback;
        }
        return text.strip();
    }

    private record GithubFacts(String status, Set<String> urls, Object fetchedAt, String reason) {
    }

    @SuppressWarnings("unchecked")
    private int question(AiTask task, JsonNode result) {
        Map<String, Object> session = mapper.lockSession(task.getResourceId(), task.getUserId());
        if (session == null || !"PREPARING".equals(String.valueOf(session.get("status")))) {
            throw BusinessException.conflict("训练会话状态已变化", "INVALID_STATE");
        }
        Map<String, Object> payload = json.map(task.getInputSnapshotJson());
        Map<String, Object> value = json.map(result);
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) payload.get("candidateQuestions");
        String mode = String.valueOf(payload.get("mode"));
        String sourceId = value.get("sourceQuestionId") == null ? null : String.valueOf(value.get("sourceQuestionId"));
        if ("REUSE_ONLY".equals(mode)) {
            if (candidates.size() != 1 || sourceId == null
                    || !sourceId.equals(String.valueOf(candidates.get(0).get("id")))) {
                throw BusinessException.aiSchema("错题重练必须复用唯一原题");
            }
        }
        String questionId;
        int version;
        if (sourceId != null) {
            String finalSourceId = sourceId;
            Map<String, Object> candidate = candidates.stream()
                    .filter(item -> finalSourceId.equals(String.valueOf(item.get("id")))).findFirst()
                    .orElseThrow(() -> BusinessException.aiSchema("复用题目不在输入候选中"));
            Map<String, Object> existing = mapper.question(finalSourceId);
            if (existing == null) throw BusinessException.aiSchema("复用题目不存在");
            if (((Number) existing.get("question_version")).intValue() != ((Number) candidate.get("version")).intValue()) {
                throw BusinessException.aiSchema("复用题目版本不一致");
            }
            questionId = finalSourceId;
            version = ((Number) existing.get("question_version")).intValue();
        } else {
            List<String> skills = ((List<Object>) value.getOrDefault("skillCodes", List.of())).stream().map(String::valueOf).toList();
            String allowedSkill = String.valueOf(((Map<String, Object>) payload.get("planItem")).get("skillCode"));
            if (skills.isEmpty() || !skills.contains(allowedSkill)) {
                throw BusinessException.aiSchema("生成题目技能不符合计划项");
            }
            for (String skill : skills) profiles.requireSkill(task.getUserId(), skill);
            String stem = requiredText(value, "stem", 3000);
            String difficulty = requiredText(value, "difficulty", 16);
            if (!Set.of("EASY", "MEDIUM", "HARD").contains(difficulty)) {
                throw BusinessException.aiSchema("题目难度非法");
            }
            String type = requiredText(value, "type", 32);
            if (!Set.of("TEXT_SCENARIO", "TEXT_KNOWLEDGE").contains(type)) {
                throw BusinessException.aiSchema("题目类型非法");
            }
            Map<String, Object> rubric = (Map<String, Object>) value.get("rubric");
            if (rubric == null) throw BusinessException.aiSchema("生成题目缺少 rubric");
            evaluations.validateRubric(rubric);
            String reference = requiredText(value, "referenceAnswer", 5000);
            List<Object> citations = (List<Object>) value.getOrDefault("citations", List.of());
            if (!citations.isEmpty() && ((List<Object>) payload.get("knowledgeRefs")).isEmpty()) {
                throw BusinessException.aiSchema("生成题目包含未授权引用");
            }
            questionId = UUID.randomUUID().toString();
            version = 1;
            // fake 模型生成的题标记 FAKE 环境：真实选题查询（questionCandidates）排除 FAKE，
            // 防止测试环境的模板题进入真实用户的练习池
            String modelProvider = value.get("modelProvider") == null ? "" : String.valueOf(value.get("modelProvider"));
            String generationEnv = modelProvider.toLowerCase(Locale.ROOT).contains("fake") ? "FAKE" : "PROD";
            mapper.insertGeneratedQuestion(questionId, stem, type, json.stringify(skills), difficulty, reference,
                    json.stringify(rubric), json.stringify(citations), task.getId(), task.getUserId(), generationEnv);
        }
        if (mapper.questionReady(task.getResourceId(), questionId, version) == 0) {
            throw BusinessException.conflict("训练会话状态已变化", "INVALID_STATE");
        }
        return version;
    }

    @SuppressWarnings("unchecked")
    private int evaluate(AiTask task, JsonNode result) {
        Map<String, Object> answer = mapper.lockAnswer(task.getResourceId(), task.getUserId());
        if (answer == null || !"EVALUATING".equals(String.valueOf(answer.get("status")))) {
            throw BusinessException.conflict("训练回答状态已变化", "INVALID_STATE");
        }
        Map<String, Object> session = mapper.lockSession(String.valueOf(answer.get("session_id")), task.getUserId());
        Map<String, Object> question = mapper.question(String.valueOf(session.get("question_id")));
        Map<String, Object> value = json.map(result);
        Map<String, Object> evaluation = (Map<String, Object>) value.get("evaluation");
        List<Map<String, Object>> skillScores = (List<Map<String, Object>>) value.getOrDefault("skillScores", List.of());
        if (evaluation == null) throw BusinessException.aiSchema("评价结果缺少 evaluation");
        List<String> skills = json.convert(json.parse(String.valueOf(question.get("skill_codes_json"))), List.class);
        EvaluationService.EvaluationResult normalized = evaluations.normalize(evaluation, skillScores, skills, task.getResourceId());
        normalized.evaluation().put("referenceAnswer", question.get("reference_answer"));
        if (mapper.evaluated(task.getResourceId(), json.stringify(normalized.evaluation())) == 0) {
            throw BusinessException.conflict("训练回答状态已变化", "INVALID_STATE");
        }
        mapper.updateSessionStatus(String.valueOf(session.get("id")), "COMPLETED");
        String questionId = String.valueOf(question.get("id"));
        String answerClass = normalized.evaluation().get("answerClass") == null ? null
                : String.valueOf(normalized.evaluation().get("answerClass"));
        if ("VALID".equals(String.valueOf(normalized.evaluation().get("evidenceStatus")))) {
            ability.addIndependent(task.getUserId(), "LEARNING_ANSWER", task.getResourceId(), task.getId(),
                    normalized.skillScores(), String.valueOf(normalized.evaluation().get("modelVersion")),
                    String.valueOf(normalized.evaluation().get("promptVersion")));
            BigDecimal overall = new BigDecimal(String.valueOf(normalized.evaluation().get("overallScore")));
            if (overall.compareTo(BigDecimal.valueOf(60)) < 0) {
                mapper.wrong(UUID.randomUUID().toString(), task.getUserId(), questionId, task.getResourceId(), "WRONG_ANSWER");
            } else {
                mapper.resolve(task.getUserId(), questionId, task.getResourceId());
            }
            learning.completePractice(String.valueOf(session.get("plan_id")), String.valueOf(session.get("plan_item_id")));
        } else if ("UNKNOWN".equals(answerClass)) {
            // 用户明确回答"不会"：不伪造分数、不计技能证据、不完成计划项，
            // 但写入错题本（cause=UNKNOWN）进入待复习与弱项推荐路径
            mapper.wrong(UUID.randomUUID().toString(), task.getUserId(), questionId, task.getResourceId(), "UNKNOWN");
        }
        return 1;
    }

    private String requiredText(Map<String, Object> value, String field, int max) {
        Object raw = value.get(field);
        if (!(raw instanceof String text) || text.isBlank() || text.length() > max) {
            throw BusinessException.aiSchema(field + " 缺失或长度非法");
        }
        return text.strip();
    }

    private int requiredInt(Object value, int min, int max) {
        if (!(value instanceof Number number) || number.intValue() < min || number.intValue() > max) {
            throw BusinessException.aiSchema("数值字段超出有效范围");
        }
        return number.intValue();
    }
}
