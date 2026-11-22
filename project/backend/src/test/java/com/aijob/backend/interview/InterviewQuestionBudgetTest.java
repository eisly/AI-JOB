package com.aijob.backend.interview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aijob.backend.ability.AbilityMapper;
import com.aijob.backend.ability.AbilityService;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.learning.EvaluationService;
import com.aijob.backend.learning.LearningMapper;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.task.AiTask;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 总提问数量预算的单元用例（design.md 8.8 实验 A/B/C/E/F/J/K/N，以及报告口径）。
 */
class InterviewQuestionBudgetTest {

    private static final String USER = "user";
    private static final String INTERVIEW = "interview";
    private static final String TASK_ID = "task";
    private static final String QUESTION_ID = "question";
    private static final String ANSWER_ID = "answer";

    private final JsonService json = new JsonService(JsonMapper.builder().build());

    private InterviewMapper mapper;
    private InterviewService interviews;
    private InterviewResultHandler handler;
    private AiTask task;

    private void setUp(Map<String, Object> session, int asked) {
        mapper = mock(InterviewMapper.class);
        interviews = mock(InterviewService.class);
        EvaluationService evaluations = mock(EvaluationService.class);
        handler = new InterviewResultHandler(mapper, interviews, evaluations,
                mock(AbilityService.class), mock(AbilityMapper.class), mock(LearningMapper.class),
                mock(ProfileService.class), json);
        task = new AiTask();
        task.setId(TASK_ID);
        task.setUserId(USER);
        task.setResourceId(INTERVIEW);
        task.setOperation("INTERVIEW_TURN");
        task.setInputSnapshotJson(json.stringify(Map.of("latestAnswer",
                Map.of("messageId", ANSWER_ID, "questionMessageId", QUESTION_ID, "content", "回答"))));
        when(mapper.lockSession(INTERVIEW, USER)).thenReturn(session);
        when(mapper.session(INTERVIEW, USER)).thenReturn(session);
        when(mapper.askedQuestions(INTERVIEW)).thenReturn(asked);
        when(mapper.message(ANSWER_ID, INTERVIEW, USER)).thenReturn(Map.of("role", "USER", "question_no", 1,
                "follow_up_no", 0, "question_message_id", QUESTION_ID));
        when(mapper.message(QUESTION_ID, INTERVIEW, USER)).thenReturn(Map.of("role", "ASSISTANT", "question_no", 1,
                "follow_up_no", 0, "skill_codes_json", "[]"));
        when(mapper.evaluationByAnswer(ANSWER_ID)).thenReturn(null);
        when(evaluations.normalize(any(), any(), any(), any())).thenReturn(
                new EvaluationService.EvaluationResult(Map.of("evidenceStatus", "INSUFFICIENT"), List.of()));
    }

    private Map<String, Object> totalAsked(int limit, int currentQuestion, int currentFollowUp) {
        return Map.of("status", "GENERATING", "question_limit", limit,
                "current_question_no", currentQuestion, "current_follow_up_no", currentFollowUp,
                "version", 7, "pending_task_id", TASK_ID, "question_count_rule", "TOTAL_ASKED");
    }

    private Map<String, Object> mainOnly(int limit, int currentQuestion, int currentFollowUp) {
        return Map.of("status", "GENERATING", "question_limit", limit,
                "current_question_no", currentQuestion, "current_follow_up_no", currentFollowUp,
                "version", 7, "pending_task_id", TASK_ID, "question_count_rule", "MAIN_ONLY");
    }

    private JsonNode output(String action, Map<String, Object> question) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("action", action);
        value.put("question", question);
        value.put("answerEvaluation", Map.of("evaluation", Map.of()));
        return json.tree(value);
    }

    private Map<String, Object> question(String content, int questionNo, int followUpNo) {
        return Map.of("content", content, "questionNo", questionNo, "followUpNo", followUpNo,
                "skillCodes", List.of());
    }

    private void verifyNoQuestionInserted() {
        verify(mapper, never()).insertMessage(any(), any(), anyInt(), any(), any(), any(), anyInt(), anyInt(),
                any(), any(), any());
    }

    private void verifyReportRequested() {
        verify(interviews).reportTask(eq(USER), eq(INTERVIEW), anyString(), anyString(), any(), any(),
                eq("NORMAL"), isNull());
    }

    @Test
    void budgetA_fiveMainQuestionsStopAtFive() {
        setUp(totalAsked(5, 5, 0), 5);
        handler.success(task, output("ASK", question("第六题", 6, 0)));
        verifyNoQuestionInserted();
        verifyReportRequested();
    }

    @Test
    void budgetB_twoMainThreeFollowUpsOccupiesFifthSlot() {
        setUp(totalAsked(5, 2, 1), 4);
        handler.success(task, output("FOLLOW_UP", question("第二个主问题的第二次追问", 2, 2)));
        verify(mapper).insertMessage(any(), eq(INTERVIEW), anyInt(), eq("ASSISTANT"), isNull(),
                eq("第二个主问题的第二次追问"), eq(2), eq(2), isNull(), any(), eq(TASK_ID));
        verify(mapper).updateTurn(INTERVIEW, "WAITING_ANSWER", TASK_ID, 2, 2, 0);
        verify(interviews, never()).reportTask(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void budgetC_lastSlotAllowsFollowUp() {
        setUp(totalAsked(5, 2, 1), 4);
        handler.success(task, output("FOLLOW_UP", question("占用最后一个额度的追问", 2, 2)));
        verify(mapper).insertMessage(any(), eq(INTERVIEW), anyInt(), eq("ASSISTANT"), isNull(),
                eq("占用最后一个额度的追问"), eq(2), eq(2), isNull(), any(), eq(TASK_ID));
    }

    @Test
    void budgetE_lastAnswerEvaluatedThenEnd() {
        setUp(totalAsked(5, 5, 0), 5);
        handler.success(task, output("ASK", question("越界的新主问题", 6, 0)));
        verify(mapper).insertEvaluation(anyString(), eq(INTERVIEW), eq(ANSWER_ID), eq(TASK_ID), anyString(),
                anyString(), any());
        verifyNoQuestionInserted();
        verifyReportRequested();
    }

    @Test
    void budgetF_modelOverrunIgnored() {
        setUp(totalAsked(5, 2, 1), 5);
        handler.success(task, output("FOLLOW_UP", question("额度耗尽后模型仍要求的追问", 2, 2)));
        verifyNoQuestionInserted();
        verifyReportRequested();
    }

    @Test
    void budgetJ_saveFailureKeepsAsked() {
        setUp(totalAsked(5, 2, 1), 4);
        doThrow(new IllegalStateException("问题保存失败")).when(mapper).insertMessage(any(), any(), anyInt(), any(),
                any(), any(), anyInt(), anyInt(), any(), any(), any());
        assertThrows(IllegalStateException.class,
                () -> handler.success(task, output("FOLLOW_UP", question("无法保存的追问", 2, 2))));
        verify(mapper, never()).updateTurn(any(), any(), any(), anyInt(), anyInt(), anyInt());
        verify(interviews, never()).reportTask(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void budgetK_staleTaskRejected() {
        setUp(Map.of("status", "GENERATING", "question_limit", 5, "current_question_no", 2,
                "current_follow_up_no", 1, "version", 7, "pending_task_id", "newer-task",
                "question_count_rule", "TOTAL_ASKED"), 4);
        assertThrows(BusinessException.class,
                () -> handler.success(task, output("FOLLOW_UP", question("旧任务带回的追问", 2, 2))));
        verify(mapper, never()).insertEvaluation(any(), any(), any(), any(), any(), any(), any());
        verifyNoQuestionInserted();
        verify(interviews, never()).reportTask(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void budgetN_mainOnlyUnchanged() {
        setUp(mainOnly(3, 1, 2), 5);
        handler.success(task, output("ASK", question("旧规则下的下一主问题", 2, 0)));
        verify(mapper).insertMessage(any(), eq(INTERVIEW), anyInt(), eq("ASSISTANT"), isNull(),
                eq("旧规则下的下一主问题"), eq(2), eq(0), isNull(), any(), eq(TASK_ID));
        verify(interviews, never()).reportTask(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void reportAddsAnsweredAskCountForTotalAskedSessions() {
        mapper = mock(InterviewMapper.class);
        interviews = mock(InterviewService.class);
        EvaluationService evaluations = mock(EvaluationService.class);
        AbilityMapper abilityMapper = mock(AbilityMapper.class);
        handler = new InterviewResultHandler(mapper, interviews, evaluations,
                mock(AbilityService.class), abilityMapper, mock(LearningMapper.class),
                mock(ProfileService.class), json);
        AiTask report = new AiTask();
        report.setId("report-task");
        report.setUserId(USER);
        report.setResourceId(INTERVIEW);
        report.setOperation("INTERVIEW_REPORT");
        report.setInputSnapshotJson(json.stringify(Map.of("completionType", "NORMAL",
                "questionCountRule", "TOTAL_ASKED", "askedQuestionCount", 5)));
        when(mapper.lockSession(INTERVIEW, USER)).thenReturn(Map.of("status", "ENDING",
                "pending_task_id", "report-task", "question_limit", 5, "question_count_rule", "TOTAL_ASKED"));
        when(evaluations.normalize(any(), any(), any(), any())).thenReturn(
                new EvaluationService.EvaluationResult(
                        new LinkedHashMap<>(Map.of("evidenceStatus", "VALID")), List.of()));
        when(abilityMapper.interviewEvidenceIds(INTERVIEW)).thenReturn(List.of());
        List<Map<String, Object>> evaluationsRows = new java.util.ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            evaluationsRows.add(Map.of("answer_message_id", "a" + index, "overall_score", 70,
                    "evaluation_json", json.stringify(Map.of("evidenceStatus", "VALID"))));
        }
        when(mapper.evaluations(INTERVIEW)).thenReturn(evaluationsRows);
        when(mapper.message("a1", INTERVIEW, USER)).thenReturn(Map.of("question_no", 1, "question_message_id", "q1"));
        when(mapper.message("a2", INTERVIEW, USER)).thenReturn(Map.of("question_no", 1, "question_message_id", "q1f1"));
        when(mapper.message("a3", INTERVIEW, USER)).thenReturn(Map.of("question_no", 1, "question_message_id", "q1f2"));
        when(mapper.message("a4", INTERVIEW, USER)).thenReturn(Map.of("question_no", 2, "question_message_id", "q2"));
        when(mapper.message("a5", INTERVIEW, USER)).thenReturn(Map.of("question_no", 2, "question_message_id", "q2f1"));

        handler.success(report, json.tree(Map.of("evaluation", Map.of("evidenceStatus", "VALID"))));

        ArgumentCaptor<String> reportJson = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertReport(anyString(), eq(INTERVIEW), eq("report-task"), reportJson.capture(), any(),
                eq("NORMAL"));
        Map<String, Object> saved = json.map(reportJson.getValue());
        assertEquals(2, ((Number) saved.get("answeredQuestionCount")).intValue());
        assertEquals(5, ((Number) saved.get("answeredAskCount")).intValue());
        assertEquals(5, ((Number) saved.get("askedQuestionCount")).intValue());
        assertEquals("TOTAL_ASKED", saved.get("questionCountRule"));
    }

}