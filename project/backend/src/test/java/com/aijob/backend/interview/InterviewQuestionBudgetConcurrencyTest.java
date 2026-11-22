package com.aijob.backend.interview;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aijob.backend.ability.AbilityMapper;
import com.aijob.backend.ability.AbilityService;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.learning.EvaluationService;
import com.aijob.backend.learning.LearningMapper;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.task.AiTask;

import tools.jackson.databind.json.JsonMapper;

/**
 * 实验 I（并发争抢最后额度）的可执行近似用例：
 * 第二个回调到达时会话的 pending_task_id 已被新任务覆盖，因此只有先到者能创建新题。
 *
 * <p>真正的双线程并发（隔离库 + 真实事务/行锁）由
 * {@code InterviewQuestionBudgetIntegrationTest} 验证，本类仅覆盖拒绝旧回调的分支。
 */
class InterviewQuestionBudgetConcurrencyTest {

    private static final String USER = "user";
    private static final String INTERVIEW = "interview";
    private static final String TASK_ID = "task";
    private static final String QUESTION_ID = "question";
    private static final String ANSWER_ID = "answer";

    private final JsonService json = new JsonService(JsonMapper.builder().build());

    @Test
    void budgetI_secondCallbackLosesLastSlot() {
        InterviewMapper mapper = mock(InterviewMapper.class);
        InterviewService interviews = mock(InterviewService.class);
        EvaluationService evaluations = mock(EvaluationService.class);
        InterviewResultHandler handler = new InterviewResultHandler(mapper, interviews, evaluations,
                mock(AbilityService.class), mock(AbilityMapper.class), mock(LearningMapper.class),
                mock(ProfileService.class), json);
        AiTask task = new AiTask();
        task.setId(TASK_ID);
        task.setUserId(USER);
        task.setResourceId(INTERVIEW);
        task.setOperation("INTERVIEW_TURN");
        task.setInputSnapshotJson(json.stringify(Map.of("latestAnswer",
                Map.of("messageId", ANSWER_ID, "questionMessageId", QUESTION_ID, "content", "回答"))));
        when(mapper.lockSession(INTERVIEW, USER)).thenReturn(Map.of("status", "GENERATING",
                "question_limit", 5, "current_question_no", 2, "current_follow_up_no", 1, "version", 7,
                "pending_task_id", "winning-task", "question_count_rule", "TOTAL_ASKED"));
        when(mapper.askedQuestions(INTERVIEW)).thenReturn(4);
        when(mapper.message(ANSWER_ID, INTERVIEW, USER)).thenReturn(Map.of("role", "USER", "question_no", 2,
                "follow_up_no", 1, "question_message_id", QUESTION_ID));
        when(mapper.message(QUESTION_ID, INTERVIEW, USER)).thenReturn(Map.of("role", "ASSISTANT",
                "question_no", 2, "follow_up_no", 1, "skill_codes_json", "[]"));
        when(mapper.evaluationByAnswer(ANSWER_ID)).thenReturn(null);
        when(evaluations.normalize(any(), any(), any(), any())).thenReturn(
                new EvaluationService.EvaluationResult(Map.of("evidenceStatus", "INSUFFICIENT"), List.of()));

        Map<String, Object> value = Map.of("action", "FOLLOW_UP",
                "question", Map.of("content", "竞争最后一个额度的追问", "questionNo", 2, "followUpNo", 2,
                        "skillCodes", List.of()),
                "answerEvaluation", Map.of("evaluation", Map.of()));

        assertThrows(BusinessException.class, () -> handler.success(task, json.tree(value)));
        verify(mapper, never()).insertMessage(any(), any(), anyInt(), any(), any(), any(), anyInt(), anyInt(),
                any(), any(), any());
        verify(interviews, never()).reportTask(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void budgetH_duplicateCallbackDoesNotIncreaseAsked() {
        InterviewMapper mapper = mock(InterviewMapper.class);
        InterviewService interviews = mock(InterviewService.class);
        EvaluationService evaluations = mock(EvaluationService.class);
        InterviewResultHandler handler = new InterviewResultHandler(mapper, interviews, evaluations,
                mock(AbilityService.class), mock(AbilityMapper.class), mock(LearningMapper.class),
                mock(ProfileService.class), json);
        AiTask task = new AiTask();
        task.setId(TASK_ID);
        task.setUserId(USER);
        task.setResourceId(INTERVIEW);
        task.setOperation("INTERVIEW_TURN");
        task.setInputSnapshotJson(json.stringify(Map.of("latestAnswer",
                Map.of("messageId", ANSWER_ID, "questionMessageId", QUESTION_ID, "content", "回答"))));
        when(mapper.lockSession(INTERVIEW, USER)).thenReturn(Map.of("status", "WAITING_ANSWER",
                "question_limit", 5, "current_question_no", 1, "current_follow_up_no", 0, "version", 8,
                "pending_task_id", TASK_ID, "question_count_rule", "TOTAL_ASKED"));
        when(mapper.askedQuestions(INTERVIEW)).thenReturn(2);

        Map<String, Object> value = Map.of("action", "ASK",
                "question", Map.of("content", "重复回调不应再出题", "questionNo", 2, "followUpNo", 0,
                        "skillCodes", List.of()),
                "answerEvaluation", Map.of("evaluation", Map.of()));

        assertThrows(BusinessException.class, () -> handler.success(task, json.tree(value)));
        verify(mapper, never()).insertMessage(any(), any(), anyInt(), any(), any(), any(), anyInt(), anyInt(),
                any(), any(), eq(TASK_ID));
    }
}
