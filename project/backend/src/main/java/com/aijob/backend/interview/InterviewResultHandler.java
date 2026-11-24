package com.aijob.backend.interview;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.aijob.backend.ability.AbilityMapper;
import com.aijob.backend.ability.AbilityService;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.learning.EvaluationService;
import com.aijob.backend.learning.LearningMapper;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.task.AiTask;
import com.aijob.backend.task.TaskResultHandler;

import tools.jackson.databind.JsonNode;

@Component
public class InterviewResultHandler implements TaskResultHandler {

    private final InterviewMapper mapper;
    private final InterviewService interviews;
    private final EvaluationService evaluations;
    private final AbilityService ability;
    private final AbilityMapper abilityMapper;
    private final LearningMapper learning;
    private final ProfileService profiles;
    private final JsonService json;

    public InterviewResultHandler(InterviewMapper mapper, InterviewService interviews, EvaluationService evaluations,
                                  AbilityService ability, AbilityMapper abilityMapper, LearningMapper learning,
                                  ProfileService profiles, JsonService json) {
        this.mapper = mapper;
        this.interviews = interviews;
        this.evaluations = evaluations;
        this.ability = ability;
        this.abilityMapper = abilityMapper;
        this.learning = learning;
        this.profiles = profiles;
        this.json = json;
    }

    @Override
    public Set<String> operations() {
        return Set.of("INTERVIEW_TURN", "INTERVIEW_REPORT");
    }

    @Override
    public int success(AiTask task, JsonNode result) {
        return "INTERVIEW_TURN".equals(task.getOperation()) ? turn(task, result) : report(task, result);
    }

    @Override
    public void failure(AiTask task, JsonNode error) {
        boolean reportTask = "INTERVIEW_REPORT".equals(task.getOperation());
        Map<String, Object> session = reportTask
                ? mapper.lockSession(task.getResourceId(), task.getUserId())
                : mapper.session(task.getResourceId(), task.getUserId());
        if (session == null) return;
        String status;
        if (reportTask) {
            if (!task.getId().equals(session.get("pending_task_id"))) return;
            status = Boolean.TRUE.equals(json.map(task.getInputSnapshotJson()).get("reevaluate"))
                    ? "COMPLETED" : "WAITING_ANSWER";
        } else {
            Map<String, Object> payload = json.map(task.getInputSnapshotJson());
            status = payload.get("latestAnswer") == null ? "FAILED" : "WAITING_ANSWER";
        }
        mapper.updateTaskStatus(task.getResourceId(), status, task.getId());
    }

    @Override
    public void prepareRetry(AiTask original, String newTaskId) {
        if ("INTERVIEW_REPORT".equals(original.getOperation())) {
            Map<String, Object> session = mapper.lockSession(original.getResourceId(), original.getUserId());
            boolean reevaluate = Boolean.TRUE.equals(json.map(original.getInputSnapshotJson()).get("reevaluate"));
            String expectedStatus = reevaluate ? "COMPLETED" : "WAITING_ANSWER";
            if (session == null || !expectedStatus.equals(session.get("status"))
                    || !original.getId().equals(session.get("pending_task_id"))) {
                throw BusinessException.conflict("面试报告状态已变化", "INVALID_STATE");
            }
            mapper.updateTaskStatus(original.getResourceId(), "ENDING", newTaskId);
        } else {
            Map<String, Object> payload = json.map(original.getInputSnapshotJson());
            mapper.updateTaskStatus(original.getResourceId(), payload.get("latestAnswer") == null ? "PREPARING" : "GENERATING", newTaskId);
        }
    }

    @SuppressWarnings("unchecked")
    private int turn(AiTask task, JsonNode result) {
        Map<String, Object> session = mapper.lockSession(task.getResourceId(), task.getUserId());
        if (session == null || !Set.of("PREPARING", "GENERATING").contains(String.valueOf(session.get("status")))) {
            throw BusinessException.conflict("面试状态已变化", "INVALID_STATE");
        }
        Map<String, Object> payload = json.map(task.getInputSnapshotJson());
        Map<String, Object> latestAnswer = (Map<String, Object>) payload.get("latestAnswer");
        Map<String, Object> value = json.map(result);
        if (latestAnswer == null && value.get("answerEvaluation") != null) {
            throw BusinessException.aiSchema("首题不得携带回答评价");
        }
        if (latestAnswer != null) {
            evaluateAnswer(task, latestAnswer, (Map<String, Object>) value.get("answerEvaluation"));
        }
        String action = String.valueOf(value.get("action"));
        if (!Set.of("ASK", "FOLLOW_UP", "FINISH").contains(action)) {
            throw BusinessException.aiSchema("面试下一步 action 非法");
        }
        Map<String, Object> question = (Map<String, Object>) value.get("question");
        if ("FINISH".equals(action) && question != null || !"FINISH".equals(action) && question == null) {
            throw BusinessException.aiSchema("面试 action 与 question 不匹配");
        }
        int limit = ((Number) session.get("question_limit")).intValue();
        int currentQuestion = ((Number) session.get("current_question_no")).intValue();
        int currentFollowUp = ((Number) session.get("current_follow_up_no")).intValue();
        boolean automaticFinish = "FINISH".equals(action);
        if (latestAnswer != null) {
            Map<String, Object> answered = mapper.message(String.valueOf(latestAnswer.get("questionMessageId")),
                    task.getResourceId(), task.getUserId());
            int answeredNo = ((Number) answered.get("question_no")).intValue();
            automaticFinish |= answeredNo >= limit && !"FOLLOW_UP".equals(action);
        }
        if (!automaticFinish) {
            int questionNo = requiredInt(question.get("questionNo"), 1, 50);
            int followUpNo = requiredInt(question.get("followUpNo"), 0, 2);
            if ("FOLLOW_UP".equals(action)) {
                if (questionNo != currentQuestion || followUpNo != currentFollowUp + 1 || followUpNo > 2) {
                    throw BusinessException.aiSchema("追问序号或次数非法");
                }
            } else if (questionNo != currentQuestion + 1 || followUpNo != 0 || questionNo > limit) {
                throw BusinessException.aiSchema("下一道面试问题序号非法");
            }
        }
        if (automaticFinish) {
            Map<String, Object> current = mapper.session(task.getResourceId(), task.getUserId());
            interviews.reportTask(task.getUserId(), task.getResourceId(),
                    "/internal/business/v1/interviews/" + task.getResourceId() + "/report", UUID.randomUUID().toString(),
                    Map.of("interviewId", task.getResourceId(), "automatic", true), current, "NORMAL", null);
            return ((Number) current.get("version")).intValue() + 1;
        }
        String content = requiredText(question, "content", 3000);
        List<String> skills = ((List<Object>) question.getOrDefault("skillCodes", List.of())).stream().map(String::valueOf).toList();
        for (String skill : skills) profiles.requireSkill(task.getUserId(), skill);
        int questionNo = ((Number) question.get("questionNo")).intValue();
        int followUpNo = ((Number) question.get("followUpNo")).intValue();
        int seq = mapper.nextSeq(task.getResourceId());
        mapper.insertMessage(UUID.randomUUID().toString(), task.getResourceId(), seq, "ASSISTANT", null, content,
                questionNo, followUpNo, null, json.stringify(skills), task.getId());
        mapper.updateTurn(task.getResourceId(), "WAITING_ANSWER", task.getId(), questionNo, followUpNo, seq);
        return ((Number) session.get("version")).intValue() + 1;
    }

    @SuppressWarnings("unchecked")
    private void evaluateAnswer(AiTask task, Map<String, Object> latestAnswer, Map<String, Object> input) {
        if (input == null) throw BusinessException.aiSchema("回答轮次缺少 answerEvaluation");
        String answerId = String.valueOf(latestAnswer.get("messageId"));
        String questionId = String.valueOf(latestAnswer.get("questionMessageId"));
        if (mapper.evaluationByAnswer(answerId) != null) {
            throw BusinessException.conflict("当前回答已评价", "INVALID_STATE");
        }
        Map<String, Object> answer = mapper.message(answerId, task.getResourceId(), task.getUserId());
        Map<String, Object> question = mapper.message(questionId, task.getResourceId(), task.getUserId());
        if (answer == null || question == null || !"USER".equals(String.valueOf(answer.get("role")))
                || !"ASSISTANT".equals(String.valueOf(question.get("role")))
                || !answerId.equals(String.valueOf(latestAnswer.get("messageId")))) {
            throw BusinessException.aiSchema("latestAnswer 引用无效");
        }
        Map<String, Object> evaluation = (Map<String, Object>) input.get("evaluation");
        List<Map<String, Object>> skillScores = (List<Map<String, Object>>) input.getOrDefault("skillScores", List.of());
        if (evaluation == null) throw BusinessException.aiSchema("answerEvaluation 缺少 evaluation");
        List<String> skills = json.convert(json.parse(String.valueOf(question.get("skill_codes_json"))), List.class);
        EvaluationService.EvaluationResult normalized = evaluations.normalize(evaluation, skillScores, skills, answerId);
        mapper.insertEvaluation(UUID.randomUUID().toString(), task.getResourceId(), answerId, task.getId(),
                json.stringify(normalized.evaluation()), AbilityService.RUBRIC, normalized.evaluation().get("overallScore"));
        if ("VALID".equals(String.valueOf(normalized.evaluation().get("evidenceStatus")))) {
            ability.addIndependent(task.getUserId(), "INTERVIEW_ANSWER", answerId, task.getId(), normalized.skillScores(),
                    String.valueOf(normalized.evaluation().get("modelVersion")), String.valueOf(normalized.evaluation().get("promptVersion")));
        }
    }

    @SuppressWarnings("unchecked")
    private int report(AiTask task, JsonNode result) {
        Map<String, Object> session = mapper.lockSession(task.getResourceId(), task.getUserId());
        if (session == null || !"ENDING".equals(session.get("status")) || !task.getId().equals(session.get("pending_task_id"))) {
            throw BusinessException.conflict("面试报告状态已变化", "INVALID_STATE");
        }
        Map<String, Object> value = json.map(result);
        Map<String, Object> payload = json.map(task.getInputSnapshotJson());
        Map<String, Object> evaluationInput = (Map<String, Object>) value.get("evaluation");
        if (evaluationInput == null) throw BusinessException.aiSchema("报告缺少 evaluation");
        EvaluationService.EvaluationResult normalized = evaluations.normalize(evaluationInput, List.of(), List.of(), task.getResourceId());
        List<Map<String, Object>> answers = mapper.messages(task.getResourceId()).stream()
                .filter(message -> "USER".equals(message.get("role"))).toList();
        Set<Object> answerIds = new HashSet<>(answers.stream().map(message -> message.get("id")).toList());
        List<BigDecimal> writtenScores = new ArrayList<>();
        for (Map<String, Object> turn : mapper.evaluations(task.getResourceId())) {
            Map<String, Object> evaluation = json.map(String.valueOf(turn.get("evaluation_json")));
            if ("VALID".equals(evaluation.get("evidenceStatus")) && turn.get("overall_score") != null
                    && answerIds.contains(turn.get("answer_message_id"))) {
                writtenScores.add(new BigDecimal(String.valueOf(turn.get("overall_score"))));
            }
        }
        BigDecimal overall = null;
        if (answers.isEmpty()) {
            normalized.evaluation().put("dimensions", List.of());
            normalized.evaluation().put("evidenceStatus", "INSUFFICIENT");
        } else {
            if (!"VALID".equals(normalized.evaluation().get("evidenceStatus"))) {
                throw BusinessException.aiSchema("存在已提交回答时，报告必须包含三维评分");
            }
            if (writtenScores.size() == answers.size()) {
                BigDecimal sum = writtenScores.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                overall = sum.divide(BigDecimal.valueOf(writtenScores.size()), 1, RoundingMode.HALF_UP);
            } else {
                if (requiredInt(value.get("reviewedAnswerCount"), 1, 62) != answers.size()) {
                    throw BusinessException.aiSchema("报告评估必须覆盖所有已提交回答");
                }
                overall = (BigDecimal) normalized.evaluation().get("overallScore");
            }
        }
        normalized.evaluation().put("overallScore", overall);
        String requestedCompletion = String.valueOf(payload.get("completionType"));
        if (!Set.of("NORMAL", "EARLY_END").contains(requestedCompletion)) {
            throw BusinessException.aiSchema("报告 completionType 非法");
        }
        String completionType = answers.isEmpty() ? "NO_VALID_ANSWER" : requestedCompletion;
        List<String> evidenceIds = abilityMapper.interviewEvidenceIds(task.getResourceId());
        List<Map<String, Object>> gaps = (List<Map<String, Object>>) value.getOrDefault("gaps", List.of());
        for (Map<String, Object> gap : gaps) {
            List<String> cited = (List<String>) gap.getOrDefault("evidenceIds", List.of());
            gap.put("evidenceIds", cited.stream().filter(evidenceIds::contains).toList());
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("evaluation", normalized.evaluation());
        report.put("gaps", gaps);
        report.put("evidenceIds", evidenceIds);
        report.put("skillEvidenceStatus", !evidenceIds.isEmpty() ? "AVAILABLE"
                : answers.isEmpty() ? "NO_VALID_ANSWER" : "NO_SKILL_SCORES");
        report.put("skillEvidenceMessage", !evidenceIds.isEmpty() ? "仅已获得有效技能评分的回答计入成长证据。"
                : answers.isEmpty() ? "本次面试未提交回答，未生成技能证据。"
                : "本次报告已评估所有回答，暂无可计入成长记录的逐技能评分。");
        report.put("answeredQuestionCount", answers.stream().map(message -> message.get("question_no")).distinct().count());
        report.put("reviewedAnswerCount", answers.size());
        report.put("completionType", completionType);
        boolean reevaluate = Boolean.TRUE.equals(payload.get("reevaluate"));
        if (reevaluate) {
            if (mapper.updateReport(task.getResourceId(), task.getId(), json.stringify(report), overall, completionType) != 1) {
                throw BusinessException.conflict("待重新评估的报告不存在", "INVALID_STATE");
            }
        } else {
            mapper.insertReport(UUID.randomUUID().toString(), task.getResourceId(), task.getId(), json.stringify(report),
                    overall, completionType);
        }
        mapper.complete(task.getResourceId(), task.getId());
        if (!reevaluate && session.get("plan_item_id") != null) {
            String itemId = String.valueOf(session.get("plan_item_id"));
            learning.updateItemStatus(itemId, "COMPLETED");
            learning.completePlan(String.valueOf(learning.itemById(itemId, task.getUserId()).get("plan_id")));
        }
        return reevaluate
                ? ((Number) mapper.report(task.getResourceId(), task.getUserId()).get("report_version")).intValue()
                : 1;
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
            throw BusinessException.aiSchema("面试问题序号非法");
        }
        return number.intValue();
    }
}
