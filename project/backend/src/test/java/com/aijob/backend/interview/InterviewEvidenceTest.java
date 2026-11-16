package com.aijob.backend.interview;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import com.aijob.backend.ability.*;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.learning.*;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.task.AiTask;
import tools.jackson.databind.json.JsonMapper;

class InterviewEvidenceTest {
    @ParameterizedTest
    @CsvSource({"VALID,true,AVAILABLE", "VALID,false,NO_SKILL_SCORES", "INSUFFICIENT,false,NO_VALID_ANSWER"})
    void reportExplainsActualSkillEvidence(String status, boolean hasSkills, String expected) {
        var mapper = mock(InterviewMapper.class);
        var evidence = mock(AbilityMapper.class);
        var json = new JsonService(JsonMapper.builder().build());
        var handler = new InterviewResultHandler(mapper, mock(InterviewService.class), new EvaluationService(),
                mock(AbilityService.class), evidence, mock(LearningMapper.class), mock(ProfileService.class), json);
        var task = new AiTask();
        task.setId("task"); task.setUserId("user"); task.setResourceId("session");
        task.setOperation("INTERVIEW_REPORT"); task.setInputSnapshotJson("{\"completionType\":\"NORMAL\"}");
        when(mapper.lockSession("session", "user")).thenReturn(Map.of("status", "ENDING"));
        var evaluation = Map.of("evidenceStatus", status, "rubricVersion", AbilityService.RUBRIC,
                "dimensions", List.of(Map.of("key", "correctness", "score", 80, "weight", 0.5),
                Map.of("key", "completeness", "score", 60, "weight", 0.3),
                Map.of("key", "expression", "score", 90, "weight", 0.2)));
        when(mapper.evaluations("session")).thenReturn("VALID".equals(status) ? List.of(Map.of(
                "evaluation_json", json.stringify(evaluation), "overall_score", 76, "answer_message_id", "answer")) : List.of());
        when(mapper.message("answer", "session", "user")).thenReturn(Map.of("question_no", 1));
        when(evidence.interviewEvidenceIds("session")).thenReturn(hasSkills ? List.of("skill-evidence") : List.of());
        handler.success(task, json.tree(Map.of("evaluation", evaluation)));
        var captured = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertReport(anyString(), eq("session"), eq("task"), captured.capture(), any(), anyString());
        var report = json.map(captured.getValue());
        assertEquals(expected, report.get("skillEvidenceStatus"));
        assertFalse(String.valueOf(report.get("skillEvidenceMessage")).isBlank());
        assertEquals(hasSkills ? List.of("skill-evidence") : List.of(), report.get("evidenceIds"));
    }
}
