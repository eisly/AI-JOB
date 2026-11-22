package com.aijob.backend.learning;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aijob.backend.ability.AbilityService;
import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.CommonResult;
import com.aijob.backend.common.Data;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.task.AiTask;
import com.aijob.backend.task.TaskService;

import tools.jackson.databind.JsonNode;

/**
 * 专项训练"整组题目"能力：一次预生成 N 道题（选择题/填空题/主观题混合），
 * 交卷时客观题在服务端自动判分，主观题交给 AI 逐题评估，最后汇总整组反馈报告。
 *
 * <p>复用既有 learning_session（session_kind=GROUP）、learning_answer（一个会话多条答案）、
 * user_mistake 与 learning_plan_item 进度，保证历史训练记录和错题本行为一致。</p>
 */
@Service
public class PracticeService {

    private static final Set<String> PRACTICE_TYPES = Set.of("CHOICE", "FILL", "SUBJECTIVE");
    private static final Set<String> DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD", "MIXED");
    private static final int MIN_TOTAL = 1;
    private static final int MAX_TOTAL = 20;
    private static final BigDecimal PASS_SCORE = BigDecimal.valueOf(60);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final LearningMapper mapper;
    private final LearningService learning;
    private final TaskService tasks;
    private final ProfileService profiles;
    private final EvaluationService evaluations;
    private final AbilityService ability;
    private final JsonService json;
    private final ApiView view;

    public PracticeService(LearningMapper mapper, LearningService learning, TaskService tasks,
                           ProfileService profiles, EvaluationService evaluations, AbilityService ability,
                           JsonService json, ApiView view) {
        this.mapper = mapper;
        this.learning = learning;
        this.tasks = tasks;
        this.profiles = profiles;
        this.evaluations = evaluations;
        this.ability = ability;
        this.json = json;
        this.view = view;
    }

    // ---------------------------------------------------------------------
    // 生成整组题目
    // ---------------------------------------------------------------------

    @Transactional
    public ResponseEntity<CommonResult<Object>> create(String userId, String route, String key,
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
        Map<String, Object> config = normalizeConfig(body);
        Map<String, Object> active = mapper.activeSession(userId, itemId);
        if (active != null) {
            throw BusinessException.conflict("当前计划项已有活动训练会话", "INVALID_STATE",
                    Map.of("sessionId", String.valueOf(active.get("id"))));
        }
        String sessionId = UUID.randomUUID().toString();
        long profileVersion = ((Number) profiles.get(userId).get("profileVersion")).longValue();
        int total = ((Number) config.get("totalQuestions")).intValue();
        Map<String, Object> payload = generatePayload(sessionId, item, config, userId);
        Map<String, Object> versions = Map.of("profileVersion", profileVersion);
        return tasks.create(userId, "POST", route, key, body, "LEARNING_PRACTICE_QUESTIONS", "PRACTICE_SET",
                sessionId, versions, payload, taskId -> {
                    mapper.insertGroupSession(sessionId, userId, planId, itemId, json.stringify(config), total);
                    mapper.updateItemStatus(itemId, "IN_PROGRESS");
                });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeConfig(Map<String, Object> body) {
        int total = Data.integer(body, "totalQuestions", 10, MIN_TOTAL, MAX_TOTAL);
        Map<String, Object> raw = Data.object(body, "typeDistribution", true);
        int choice = intValue(raw.get("choice"), "choice", total);
        int fill = intValue(raw.get("fill"), "fill", total);
        int subjective = intValue(raw.get("subjective"), "subjective", total);
        if (choice + fill + subjective != total) {
            throw BusinessException.validation("typeDistribution", "题型分布之和必须等于 totalQuestions");
        }
        String difficulty = Data.text(body, "difficulty", false, 16);
        difficulty = difficulty == null ? "MEDIUM" : difficulty.toUpperCase(Locale.ROOT);
        if (!DIFFICULTIES.contains(difficulty)) {
            throw BusinessException.validation("difficulty", "训练难度非法");
        }
        String direction = Data.text(body, "direction", false, 32);
        Map<String, Object> distribution = new LinkedHashMap<>();
        distribution.put("choice", choice);
        distribution.put("fill", fill);
        distribution.put("subjective", subjective);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("totalQuestions", total);
        config.put("typeDistribution", distribution);
        config.put("difficulty", difficulty);
        config.put("direction", direction == null ? "" : direction);
        return config;
    }

    private <T> List<T> tail(List<T> values, int limit) {
        return values.size() <= limit ? values : new ArrayList<>(values.subList(values.size() - limit, values.size()));
    }

    private int intValue(Object raw, String field, int max) {
        if (!(raw instanceof Number number) || number.intValue() < 0 || number.intValue() > max) {
            throw BusinessException.validation("typeDistribution", field + " 数量非法");
        }
        return number.intValue();
    }

    private Map<String, Object> generatePayload(String sessionId, Map<String, Object> item,
                                                Map<String, Object> config, String userId) {
        Map<String, Object> planItem = new LinkedHashMap<>();
        planItem.put("skillCode", item.get("skill_code"));
        // 计划项难度只接受 EASY/MEDIUM/HARD；MIXED 由 config.difficulty 交给 Agent 逐题分配
        String configDifficulty = String.valueOf(config.getOrDefault("difficulty", "MEDIUM"));
        planItem.put("difficulty", Set.of("EASY", "MEDIUM", "HARD").contains(configDifficulty)
                ? configDifficulty : "MEDIUM");
        Map.of("title", "title", "objective", "objective", "reason", "reason", "completion_criteria", "completionCriteria")
                .forEach((column, field) -> {
                    if (item.get(column) != null) {
                        planItem.put(field, item.get(column));
                    }
                });
        LinkedHashSet<String> excludedIds = new LinkedHashSet<>(mapper.usedQuestionIds(userId));
        excludedIds.addAll(mapper.usedSessionQuestionIds(userId));
        LinkedHashSet<String> excludedStems = new LinkedHashSet<>(mapper.usedQuestionStems(userId));
        excludedStems.addAll(mapper.usedSessionQuestionStems(userId));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId);
        payload.put("planItem", planItem);
        payload.put("config", config);
        payload.put("candidateQuestions", List.of());
        // Agent 侧契约上限各 100 条，取最近的记录，避免历史过久导致校验失败
        payload.put("excludedQuestionIds", tail(new ArrayList<>(excludedIds), 100));
        payload.put("excludedQuestionStems", tail(new ArrayList<>(excludedStems), 100));
        payload.put("knowledgeRefs", List.of());
        return payload;
    }

    // ---------------------------------------------------------------------
    // 错题回流：用原题直接创建一个单题练习集（不调用 AI，题型保持原样）
    // ---------------------------------------------------------------------

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> retryMistakeAsSet(String userId, String mistakeId) {
        Map<String, Object> mistake = mapper.mistake(mistakeId, userId);
        if (mistake == null) {
            throw BusinessException.notFound();
        }
        Map<String, Object> answer = mapper.answer(String.valueOf(mistake.get("last_answer_id")), userId);
        if (answer == null) {
            throw BusinessException.notFound();
        }
        Map<String, Object> question = mapper.question(String.valueOf(mistake.get("question_id")));
        if (question == null) {
            throw BusinessException.conflict("原题不可用", "INVALID_STATE");
        }
        Map<String, Object> session = mapper.session(String.valueOf(answer.get("session_id")), userId);
        if (session == null) {
            throw BusinessException.notFound();
        }
        String planId = String.valueOf(session.get("plan_id"));
        String itemId = String.valueOf(session.get("plan_item_id"));
        if (mapper.activeSession(userId, itemId) != null) {
            throw BusinessException.conflict("当前计划项已有活动训练会话", "INVALID_STATE");
        }
        String type = String.valueOf(question.get("type"));
        int choice = "CHOICE".equals(type) ? 1 : 0;
        int fill = "FILL".equals(type) ? 1 : 0;
        int subjective = choice + fill == 0 ? 1 : 0;
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("totalQuestions", 1);
        config.put("typeDistribution", Map.of("choice", choice, "fill", fill, "subjective", subjective));
        config.put("difficulty", String.valueOf(question.get("difficulty")));
        config.put("direction", "MISTAKE_RETRY");
        config.put("mistakeId", mistakeId);
        String setId = UUID.randomUUID().toString();
        int questionVersion = ((Number) question.get("question_version")).intValue();
        int score = question.get("score") == null ? 10 : ((Number) question.get("score")).intValue();
        mapper.insertGroupSession(setId, userId, planId, itemId, json.stringify(config), 1);
        mapper.insertSessionQuestion(UUID.randomUUID().toString(), setId, String.valueOf(question.get("id")),
                questionVersion, 1, score);
        if (mapper.groupQuestionsReady(setId, 1) == 0) {
            throw BusinessException.conflict("错题重练会话创建失败", "INVALID_STATE");
        }
        mapper.updateItemStatus(itemId, "IN_PROGRESS");
        return setVo(mapper.session(setId, userId));
    }

    // ---------------------------------------------------------------------
    // 读取整组题目
    // ---------------------------------------------------------------------

    public Map<String, Object> detail(String userId, String setId) {
        Map<String, Object> session = mapper.session(setId, userId);
        requireGroupSession(session);
        return setVo(session);
    }

    private void requireGroupSession(Map<String, Object> session) {
        if (session == null || !"GROUP".equals(String.valueOf(session.get("session_kind")))) {
            throw BusinessException.notFound();
        }
    }

    public Map<String, Object> setVo(Map<String, Object> session) {
        String setId = String.valueOf(session.get("id"));
        List<Map<String, Object>> questions = mapper.sessionQuestions(setId);
        int totalMinutes = 0;
        List<Map<String, Object>> questionVos = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> question : questions) {
            Map<String, Object> vo = practiceQuestionVo(question, index++);
            totalMinutes += ((Number) vo.get("estimatedMinutes")).intValue();
            questionVos.add(vo);
        }
        String itemId = String.valueOf(session.get("plan_item_id"));
        String title = "专项训练";
        if (itemId != null && !itemId.isBlank() && !"null".equals(itemId)) {
            Map<String, Object> item = mapper.itemById(itemId, String.valueOf(session.get("user_id")));
            if (item != null && item.get("title") != null) {
                title = String.valueOf(item.get("title"));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", setId);
        result.put("planId", session.get("plan_id"));
        result.put("planItemId", session.get("plan_item_id"));
        result.put("title", title);
        result.put("status", setStatus(String.valueOf(session.get("status"))));
        result.put("config", session.get("config_json") == null ? null
                : json.convert(json.parse(String.valueOf(session.get("config_json"))), Object.class));
        result.put("questions", questionVos);
        result.put("totalMinutes", totalMinutes);
        result.put("version", session.get("version"));
        result.put("generatedAt", view.value(session.get("created_at")));
        result.put("lastAnswerId", session.get("last_answer_id"));
        result.put("evaluationTaskId", session.get("evaluation_task_id"));
        return result;
    }

    private Map<String, Object> practiceQuestionVo(Map<String, Object> question, int index) {
        String type = String.valueOf(question.get("type"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", question.get("id"));
        result.put("index", index);
        result.put("type", type);
        result.put("skillCodes", parseList(question.get("skill_codes_json")));
        result.put("difficulty", question.get("difficulty"));
        result.put("stem", question.get("stem"));
        result.put("options", question.get("options_json") == null ? List.of()
                : json.convert(json.parse(String.valueOf(question.get("options_json"))), Object.class));
        result.put("blankCount", question.get("blank_count") == null ? 0
                : ((Number) question.get("blank_count")).intValue());
        result.put("estimatedMinutes", estimatedMinutes(type));
        result.put("score", question.get("session_score") == null ? 10
                : ((Number) question.get("session_score")).intValue());
        result.put("questionVersion", question.get("question_version"));
        result.put("sourceType", question.get("source_type"));
        result.put("sourceTitle", null);
        result.put("generationEnv", question.get("generation_env"));
        return result;
    }

    /** 会话状态 → 前端练习集状态词。 */
    private String setStatus(String status) {
        return switch (status) {
            case "PREPARING" -> "GENERATING";
            case "ACTIVE" -> "IN_PROGRESS";
            case "EVALUATING" -> "EVALUATING";
            case "COMPLETED" -> "EVALUATED";
            default -> status;
        };
    }

    private int estimatedMinutes(String type) {
        return switch (type) {
            case "CHOICE" -> 2;
            case "FILL" -> 3;
            default -> 10;
        };
    }

    // ---------------------------------------------------------------------
    // 交卷：客观题服务端判分 + 主观题异步 AI 评估
    // ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    @Transactional
    public ResponseEntity<CommonResult<Object>> submit(String userId, String setId, String route, String key,
                                                       Map<String, Object> body) {
        ResponseEntity<CommonResult<Object>> replay = tasks.replay(userId, "POST", route, key, body);
        if (replay != null) {
            return replay;
        }
        Map<String, Object> session = mapper.lockSession(setId, userId);
        requireGroupSession(session);
        if (!"ACTIVE".equals(String.valueOf(session.get("status")))) {
            throw BusinessException.conflict("当前训练组不能交卷", "INVALID_STATE",
                    Map.of("status", String.valueOf(session.get("status"))));
        }
        int actual = ((Number) session.get("version")).intValue();
        int expected = Data.integer(body, "expectedVersion", actual, 1, Integer.MAX_VALUE);
        if (expected != actual) {
            throw BusinessException.versionConflict(actual);
        }
        int duration = Data.integer(body, "durationSeconds", 0, 0, 86400);
        List<Object> rawAnswers = Data.list(body, "answers", false, 100);
        Map<String, Map<String, Object>> submitted = new LinkedHashMap<>();
        for (Object raw : rawAnswers) {
            if (!(raw instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> answer = new LinkedHashMap<>((Map<String, Object>) map);
            String questionId = Data.uuid(answer, "questionId", true);
            submitted.put(questionId, answer);
        }

        List<Map<String, Object>> questions = mapper.sessionQuestions(setId);
        if (questions.isEmpty() || questions.size() != ((Number) session.get("total_questions")).intValue()) {
            throw BusinessException.conflict("整组题目尚未就绪", "INVALID_STATE");
        }
        List<Map<String, Object>> answerRows = new ArrayList<>();
        List<Map<String, Object>> subjectivePayload = new ArrayList<>();
        String firstAnswerId = null;
        int seq = 1;
        for (Map<String, Object> question : questions) {
            String questionId = String.valueOf(question.get("id"));
            String type = String.valueOf(question.get("type"));
            Map<String, Object> answer = submitted.getOrDefault(questionId, Map.of());
            String answerId = UUID.randomUUID().toString();
            if (firstAnswerId == null) {
                firstAnswerId = answerId;
            }
            int questionVersion = ((Number) question.get("question_version")).intValue();
            int questionScore = ((Number) question.get("session_score")).intValue();
            Map<String, Object> answerJson = new LinkedHashMap<>();
            String content;
            if ("SUBJECTIVE".equals(type)) {
                Object raw = answer.get("content");
                content = raw == null ? "" : String.valueOf(raw).trim();
                if (content.length() > 10000) {
                    throw BusinessException.validation("answers", "主观题回答不能超过10000个字符");
                }
                answerJson.put("content", content);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("answerId", answerId);
                item.put("questionId", questionId);
                item.put("questionVersion", questionVersion);
                item.put("stem", question.get("stem"));
                item.put("skillCodes", parseList(question.get("skill_codes_json")));
                item.put("referenceAnswer", question.get("reference_answer"));
                item.put("rubric", json.convert(json.parse(String.valueOf(question.get("rubric_json"))), Object.class));
                item.put("answer", content.isBlank() ? "（未作答）" : content);
                subjectivePayload.add(item);
            } else if ("CHOICE".equals(type)) {
                Object raw = answer.get("choiceKey");
                String choiceKey = raw == null ? "" : String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
                boolean correct = gradeChoice(choiceKey, question.get("answer_key_json"));
                answerJson.put("choiceKey", choiceKey);
                content = json.stringify(answerJson);
                answerRows.add(objectiveRow(answerId, setId, userId, questionId, questionVersion, seq++,
                        content, answerJson, correct, correct ? questionScore : 0));
            } else {
                List<String> blanks = new ArrayList<>();
                Object raw = answer.get("blanks");
                if (raw instanceof List<?> list) {
                    for (Object item : list) {
                        blanks.add(item == null ? "" : String.valueOf(item).trim());
                    }
                }
                boolean correct = gradeFill(blanks, question.get("answer_key_json"));
                answerJson.put("blanks", blanks);
                content = json.stringify(answerJson);
                answerRows.add(objectiveRow(answerId, setId, userId, questionId, questionVersion, seq++,
                        content, answerJson, correct, correct ? questionScore : 0));
            }
            if ("SUBJECTIVE".equals(type)) {
                answerRows.add(objectiveRow(answerId, setId, userId, questionId, questionVersion, seq++,
                        content.isBlank() ? "（未作答）" : content, answerJson, null, null));
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", setId);
        payload.put("answers", subjectivePayload);
        Map<String, Object> versions = Map.of("practiceSetVersion", actual);
        String lastAnswerId = firstAnswerId;
        return tasks.create(userId, "POST", route, key, body, "LEARNING_PRACTICE_EVALUATE", "PRACTICE_EVALUATION",
                setId, versions, payload, taskId -> {
                    for (Map<String, Object> row : answerRows) {
                        mapper.insertPracticeAnswer((String) row.get("id"), setId, (String) row.get("questionId"),
                                (Integer) row.get("questionVersion"), (Integer) row.get("seq"), userId,
                                (String) row.get("content"), (String) row.get("answerJson"),
                                json.sha256(String.valueOf(row.get("content"))), taskId);
                        Object correct = row.get("correct");
                        if (correct != null) {
                            mapper.objectiveEvaluated((String) row.get("id"), (Boolean) correct,
                                    new BigDecimal(String.valueOf(row.get("score"))));
                        }
                    }
                    mapper.practiceSubmitted(setId, taskId, duration, lastAnswerId);
                });
    }

    private Map<String, Object> objectiveRow(String answerId, String sessionId, String userId, String questionId,
                                             int questionVersion, int seq, String content,
                                             Map<String, Object> answerJson, Boolean correct, Integer score) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", answerId);
        row.put("questionId", questionId);
        row.put("questionVersion", questionVersion);
        row.put("seq", seq);
        row.put("content", content);
        row.put("answerJson", json.stringify(answerJson));
        row.put("correct", correct);
        row.put("score", score == null ? 0 : score);
        return row;
    }

    /** 选择题自动判分（包级可见，便于单元测试）。 */
    boolean gradeChoice(String submittedKey, Object answerKeyJson) {
        List<String> keys = parseList(answerKeyJson);
        if (keys.isEmpty()) {
            return false;
        }
        return normalizeKey(keys.get(0)).equals(normalizeKey(submittedKey));
    }

    /** 填空题自动判分：按空位顺序逐空比对，忽略空白与英文大小写。 */
    boolean gradeFill(List<String> blanks, Object answerKeyJson) {
        List<String> keys = parseList(answerKeyJson);
        if (keys.isEmpty()) {
            return false;
        }
        for (int index = 0; index < keys.size(); index++) {
            String expected = normalizeKey(keys.get(index));
            String actual = index < blanks.size() ? normalizeKey(blanks.get(index)) : "";
            if (expected.isEmpty() || !expected.equals(actual)) {
                return false;
            }
        }
        return true;
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------------
    // 结果查询
    // ---------------------------------------------------------------------

    public Map<String, Object> result(String userId, String setId) {
        Map<String, Object> session = mapper.session(setId, userId);
        requireGroupSession(session);
        Object raw = session.get("result_json");
        if (raw != null) {
            return json.map(String.valueOf(raw));
        }
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("id", setId);
        pending.put("status", session.get("status"));
        pending.put("overallScore", null);
        pending.put("objectiveCorrectCount", 0);
        pending.put("objectiveTotalCount", 0);
        pending.put("summary", "本组训练仍在评估中，请稍后刷新");
        pending.put("strengths", List.of());
        pending.put("improvements", List.of());
        pending.put("questionResults", List.of());
        return pending;
    }

    // ---------------------------------------------------------------------
    // AI 结果落库（由 LearningResultHandler 调用）
    // ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    @Transactional
    public int applyQuestions(AiTask task, JsonNode result) {
        Map<String, Object> session = mapper.lockSession(task.getResourceId(), task.getUserId());
        requireGroupSession(session);
        if (!"PREPARING".equals(String.valueOf(session.get("status")))) {
            throw BusinessException.conflict("训练会话状态已变化", "INVALID_STATE");
        }
        int total = ((Number) session.get("total_questions")).intValue();
        Map<String, Object> value = json.map(result);
        Object rawQuestions = value.get("questions");
        if (!(rawQuestions instanceof List<?> questions) || questions.isEmpty()) {
            throw BusinessException.aiSchema("整组题目为空");
        }
        if (questions.size() != total) {
            throw BusinessException.aiSchema("题目数量与训练配置不一致");
        }
        Map<String, Object> config = session.get("config_json") == null ? Map.of()
                : json.map(String.valueOf(session.get("config_json")));
        Map<String, Object> distribution = config.get("typeDistribution") instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
        int expectedChoice = distribution.get("choice") == null ? 0 : ((Number) distribution.get("choice")).intValue();
        int expectedFill = distribution.get("fill") == null ? 0 : ((Number) distribution.get("fill")).intValue();
        int expectedSubjective = distribution.get("subjective") == null ? 0
                : ((Number) distribution.get("subjective")).intValue();

        int choice = 0;
        int fill = 0;
        int subjective = 0;
        int seq = 1;
        for (Object raw : questions) {
            if (!(raw instanceof Map<?, ?> rawQuestion)) {
                throw BusinessException.aiSchema("题目结构非法");
            }
            Map<String, Object> question = new LinkedHashMap<>((Map<String, Object>) rawQuestion);
            String type = requiredText(question, "type", 32);
            if (!PRACTICE_TYPES.contains(type)) {
                throw BusinessException.aiSchema("题目类型非法");
            }
            String stem = requiredText(question, "stem", 3000);
            if (stem.length() < 10) {
                throw BusinessException.aiSchema("题干过短");
            }
            String difficulty = requiredText(question, "difficulty", 16);
            if (!Set.of("EASY", "MEDIUM", "HARD").contains(difficulty)) {
                throw BusinessException.aiSchema("题目难度非法");
            }
            List<Object> skills = listValue(question.get("skillCodes"));
            if (skills.isEmpty()) {
                throw BusinessException.aiSchema("题目缺少技能编码");
            }
            int score = requiredInt(question.get("score"), 1, 100);
            String reference = requiredText(question, "referenceAnswer", 5000);
            Map<String, Object> rubric = mapValue(question.get("rubric"));
            if (rubric.isEmpty()) {
                throw BusinessException.aiSchema("题目缺少评分量表");
            }
            evaluations.validateRubric(rubric);
            List<Object> options = listValue(question.get("options"));
            List<Object> answerKey = listValue(question.get("answerKey"));
            int blankCount = question.get("blankCount") == null ? 0
                    : ((Number) question.get("blankCount")).intValue();
            if ("CHOICE".equals(type)) {
                choice++;
                if (options.size() < 2 || answerKey.size() != 1) {
                    throw BusinessException.aiSchema("选择题选项或正确答案非法");
                }
            } else if ("FILL".equals(type)) {
                fill++;
                if (blankCount < 1 || answerKey.size() != blankCount) {
                    throw BusinessException.aiSchema("填空题空位与标准答案不一致");
                }
            } else {
                subjective++;
                if (!options.isEmpty() || !answerKey.isEmpty()) {
                    throw BusinessException.aiSchema("主观题不得包含选项或标准答案");
                }
            }
            Object citations = question.getOrDefault("citations", List.of());
            String questionId = UUID.randomUUID().toString();
            mapper.insertGeneratedPracticeQuestion(questionId, stem, json.stringify(options), json.stringify(answerKey),
                    blankCount, score, type, json.stringify(skills), difficulty, reference, json.stringify(rubric),
                    json.stringify(citations), task.getId(), task.getUserId(), "PROD");
            mapper.insertSessionQuestion(UUID.randomUUID().toString(), task.getResourceId(), questionId, 1, seq++, score);
        }
        if (choice != expectedChoice || fill != expectedFill || subjective != expectedSubjective) {
            throw BusinessException.aiSchema("题型分布与训练配置不一致");
        }
        if (mapper.groupQuestionsReady(task.getResourceId(), total) == 0) {
            throw BusinessException.conflict("训练会话状态已变化", "INVALID_STATE");
        }
        return total;
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public int applyEvaluation(AiTask task, JsonNode result) {
        String sessionId = task.getResourceId();
        Map<String, Object> session = mapper.lockSession(sessionId, task.getUserId());
        requireGroupSession(session);
        if (!"EVALUATING".equals(String.valueOf(session.get("status")))) {
            throw BusinessException.conflict("整组训练状态已变化", "INVALID_STATE");
        }
        Map<String, Object> value = json.map(result);
        Map<String, Map<String, Object>> evaluationByAnswer = new LinkedHashMap<>();
        if (value.get("evaluations") instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> evaluationItem = new LinkedHashMap<>((Map<String, Object>) map);
                    evaluationByAnswer.put(String.valueOf(evaluationItem.get("answerId")), evaluationItem);
                }
            }
        }
        List<Map<String, Object>> answers = mapper.sessionAnswers(sessionId);
        Map<String, Map<String, Object>> questions = new LinkedHashMap<>();
        for (Map<String, Object> question : mapper.sessionQuestions(sessionId)) {
            questions.put(String.valueOf(question.get("id")), question);
        }
        BigDecimal earned = BigDecimal.ZERO;
        BigDecimal totalScore = BigDecimal.ZERO;
        int objectiveCorrect = 0;
        int objectiveTotal = 0;
        List<Map<String, Object>> questionResults = new ArrayList<>();
        Set<String> subjectiveAnswers = new LinkedHashSet<>();
        for (Map<String, Object> answer : answers) {
            String answerId = String.valueOf(answer.get("id"));
            String questionId = String.valueOf(answer.get("question_id"));
            Map<String, Object> question = questions.get(questionId);
            if (question == null) {
                continue;
            }
            int questionScore = ((Number) question.get("session_score")).intValue();
            totalScore = totalScore.add(BigDecimal.valueOf(questionScore));
            String type = String.valueOf(question.get("type"));
            if ("SUBJECTIVE".equals(type)) {
                subjectiveAnswers.add(answerId);
                Map<String, Object> evaluationItem = evaluationByAnswer.get(answerId);
                if (evaluationItem == null) {
                    throw BusinessException.aiSchema("缺少主观题评估结果: " + answerId);
                }
                Object rawEvaluation = evaluationItem.get("evaluation");
                if (!(rawEvaluation instanceof Map<?, ?> map)) {
                    throw BusinessException.aiSchema("主观题评估结构非法");
                }
                Map<String, Object> evaluation = new LinkedHashMap<>((Map<String, Object>) map);
                List<Map<String, Object>> skillScores = new ArrayList<>();
                if (evaluationItem.get("skillScores") instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> scoreMap) {
                            skillScores.add(new LinkedHashMap<>((Map<String, Object>) scoreMap));
                        }
                    }
                }
                List<String> skills = parseList(question.get("skill_codes_json"));
                EvaluationService.EvaluationResult normalized =
                        evaluations.normalize(evaluation, skillScores, skills, answerId);
                normalized.evaluation().put("referenceAnswer", question.get("reference_answer"));
                Object overallRaw = normalized.evaluation().get("overallScore");
                BigDecimal overall = overallRaw == null ? BigDecimal.ZERO
                        : new BigDecimal(String.valueOf(overallRaw));
                BigDecimal score = overall.multiply(BigDecimal.valueOf(questionScore))
                        .divide(HUNDRED, 1, RoundingMode.HALF_UP);
                mapper.practiceAnswerEvaluated(answerId, json.stringify(normalized.evaluation()), score);
                earned = earned.add(score);
                String evidence = String.valueOf(normalized.evaluation().get("evidenceStatus"));
                Object answerClassRaw = normalized.evaluation().get("answerClass");
                String answerClass = answerClassRaw == null ? null : String.valueOf(answerClassRaw);
                if ("VALID".equals(evidence)) {
                    ability.addIndependent(task.getUserId(), "LEARNING_ANSWER", answerId, task.getId(),
                            normalized.skillScores(),
                            String.valueOf(normalized.evaluation().get("modelVersion")),
                            String.valueOf(normalized.evaluation().get("promptVersion")));
                    if (overall.compareTo(PASS_SCORE) < 0) {
                        mapper.wrong(UUID.randomUUID().toString(), task.getUserId(), questionId, answerId,
                                "WRONG_ANSWER");
                    } else {
                        mapper.resolve(task.getUserId(), questionId, answerId);
                    }
                } else if ("UNKNOWN".equals(answerClass)) {
                    mapper.wrong(UUID.randomUUID().toString(), task.getUserId(), questionId, answerId, "UNKNOWN");
                }
                Map<String, Object> questionResult = new LinkedHashMap<>();
                questionResult.put("questionId", questionId);
                questionResult.put("correct", null);
                questionResult.put("score", score);
                questionResult.put("comment", dimensionComment(normalized.evaluation()));
                questionResult.put("evaluation", normalized.evaluation());
                questionResults.add(questionResult);
            } else {
                boolean correct = toBoolean(answer.get("objective_correct"));
                BigDecimal score = answer.get("score") == null ? BigDecimal.ZERO
                        : new BigDecimal(String.valueOf(answer.get("score")));
                earned = earned.add(score);
                objectiveTotal++;
                if (correct) {
                    objectiveCorrect++;
                    mapper.resolve(task.getUserId(), questionId, answerId);
                } else {
                    mapper.wrong(UUID.randomUUID().toString(), task.getUserId(), questionId, answerId,
                            "WRONG_ANSWER");
                }
                Map<String, Object> questionResult = new LinkedHashMap<>();
                questionResult.put("questionId", questionId);
                questionResult.put("correct", correct);
                questionResult.put("score", score);
                questionResult.put("correctKeys", parseList(question.get("answer_key_json")));
                questionResult.put("comment", correct ? "回答正确" : "回答错误，请对照正确答案复习");
                questionResult.put("evaluation", null);
                questionResults.add(questionResult);
            }
        }
        if (!subjectiveAnswers.equals(evaluationByAnswer.keySet())) {
            throw BusinessException.aiSchema("评估结果与主观题作答不匹配");
        }
        BigDecimal overallScore = totalScore.signum() == 0 ? null
                : earned.multiply(HUNDRED).divide(totalScore, 1, RoundingMode.HALF_UP);
        Map<String, Object> report = value.get("report") instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
        Map<String, Object> resultJson = new LinkedHashMap<>();
        resultJson.put("id", sessionId);
        resultJson.put("status", "EVALUATED");
        resultJson.put("overallScore", overallScore);
        resultJson.put("objectiveCorrectCount", objectiveCorrect);
        resultJson.put("objectiveTotalCount", objectiveTotal);
        resultJson.put("summary", report.get("summary") == null || String.valueOf(report.get("summary")).isBlank()
                ? "本组训练已完成评估。" : String.valueOf(report.get("summary")));
        resultJson.put("strengths", report.getOrDefault("strengths", List.of()));
        resultJson.put("improvements", report.getOrDefault("improvements", List.of()));
        resultJson.put("questionResults", questionResults);
        resultJson.put("evaluatedAt", Instant.now().toString());
        if (mapper.practiceCompleted(sessionId, json.stringify(resultJson)) == 0) {
            throw BusinessException.conflict("整组训练状态已变化", "INVALID_STATE");
        }
        learning.completePractice(String.valueOf(session.get("plan_id")), String.valueOf(session.get("plan_item_id")));
        return questionResults.size();
    }

    private String dimensionComment(Map<String, Object> evaluation) {
        Object raw = evaluation.get("dimensions");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && map.get("comment") != null) {
                if (builder.length() > 0) {
                    builder.append("；");
                }
                builder.append(map.get("key")).append("：").append(map.get("comment"));
            }
        }
        return builder.toString();
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return "1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return new LinkedHashMap<>();
    }

    private List<Object> listValue(Object raw) {
        if (raw instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private List<String> parseList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        try {
            List<String> result = json.convert(json.parse(String.valueOf(raw)), List.class);
            return result == null ? List.of() : result;
        } catch (RuntimeException exception) {
            return List.of();
        }
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
