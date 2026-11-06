package com.aijob.backend.interview;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.aijob.backend.ability.AbilityMapper;
import com.aijob.backend.ability.AbilityService;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.learning.EvaluationService;
import com.aijob.backend.learning.LearningMapper;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.task.AiTask;
import tools.jackson.databind.json.JsonMapper;

class InterviewResultHandlerTest {
    @Test
    void exhaustedFollowupsAdvanceToNextMainQuestion() {
        InterviewMapper mapper = mock(InterviewMapper.class);
        InterviewService interviews = mock(InterviewService.class);
        EvaluationService evaluations = mock(EvaluationService.class);
        JsonService json = new JsonService(JsonMapper.builder().build());
        InterviewResultHandler handler = new InterviewResultHandler(mapper, interviews, evaluations,
                mock(AbilityService.class), mock(AbilityMapper.class), mock(LearningMapper.class),
                mock(ProfileService.class), json);
        AiTask task = new AiTask();
        task.setId("task"); task.setUserId("user"); task.setResourceId("interview"); task.setOperation("INTERVIEW_TURN");
        task.setInputSnapshotJson(json.stringify(Map.of("latestAnswer", Map.of("messageId", "answer", "questionMessageId", "question"))));
        when(mapper.lockSession("interview", "user")).thenReturn(Map.of("status", "GENERATING", "question_limit", 3,
                "current_question_no", 1, "current_follow_up_no", 2, "version", 6));
        when(mapper.message("answer", "interview", "user")).thenReturn(Map.of("role", "USER"));
        when(mapper.message("question", "interview", "user")).thenReturn(Map.of("role", "ASSISTANT",
                "question_no", 1, "follow_up_no", 2, "skill_codes_json", "[]"));
        when(mapper.evaluationByAnswer("answer")).thenReturn(null);
        when(evaluations.normalize(any(), any(), any(), any())).thenReturn(new EvaluationService.EvaluationResult(
                Map.of("evidenceStatus", "INSUFFICIENT"), List.of()));

        handler.success(task, json.tree(Map.of("action", "ASK", "question", Map.of("content", "Next main question",
                "questionNo", 2, "followUpNo", 0, "skillCodes", List.of()), "answerEvaluation", Map.of("evaluation", Map.of()))));

        verify(mapper).updateTurn("interview", "WAITING_ANSWER", "task", 2, 0, 0);
        verifyNoInteractions(interviews);
    }
}
