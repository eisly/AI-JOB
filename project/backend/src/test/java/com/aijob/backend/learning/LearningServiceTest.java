package com.aijob.backend.learning;

import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.job.JobMapper;
import com.aijob.backend.job.JobService;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.resume.ResumeMapper;
import com.aijob.backend.task.AiTask;
import com.aijob.backend.task.TaskMapper;
import com.aijob.backend.task.TaskService;

import tools.jackson.databind.json.JsonMapper;

class LearningServiceTest {

    @Test
    void repeatedReadingCompletionPreservesOriginalCompletionTime() {
        LearningMapper mapper = mock(LearningMapper.class);
        JsonService json = new JsonService(JsonMapper.builder().build());
        LearningService service = new LearningService(mapper, mock(JobMapper.class), mock(JobService.class),
                mock(ProfileService.class), mock(ResumeMapper.class), mock(TaskService.class),
                mock(TaskMapper.class), json, new ApiView(json));
        when(mapper.item("plan", "reading", "user")).thenReturn(Map.of("kind", "READING", "status", "COMPLETED"));
        org.junit.jupiter.api.Assertions.assertTrue(service.completeReading("user", "plan", "reading"));
        verify(mapper, never()).updateItemStatus(anyString(), anyString());
    }

    @Test
    void questionViewPreservesGenerationEnvironment() {
        JsonService json = new JsonService(JsonMapper.builder().build());
        LearningService service = new LearningService(mock(LearningMapper.class), mock(JobMapper.class),
                mock(JobService.class), mock(ProfileService.class), mock(ResumeMapper.class),
                mock(TaskService.class), mock(TaskMapper.class), json, new ApiView(json));
        for (String environment : java.util.List.of("FAKE", "LEGACY", "PROD")) {
            var view = service.questionVo(Map.of("skill_codes_json", "[]", "generation_env", environment));
            org.junit.jupiter.api.Assertions.assertEquals(environment, view.get("generationEnv"));
        }
    }

    @Test
    void questionInputIncludesTheSpecificTrainingGoalAndCriteria() {
        LearningMapper mapper = mock(LearningMapper.class);
        TaskService tasks = mock(TaskService.class);
        ProfileService profiles = mock(ProfileService.class);
        JsonService json = new JsonService(JsonMapper.builder().build());
        LearningService service = new LearningService(mapper, mock(JobMapper.class), mock(JobService.class),
                profiles, mock(ResumeMapper.class), tasks, mock(TaskMapper.class), json, new ApiView(json));
        String planId = java.util.UUID.randomUUID().toString();
        String itemId = java.util.UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("planId", planId, "planItemId", itemId);
        when(mapper.lockItem(planId, itemId, "user")).thenReturn(Map.of(
                "kind", "PRACTICE", "skill_code", "JAVA", "title", "接口并发控制",
                "objective", "修复重复提交", "completion_criteria", "说明幂等键和并发测试",
                "reason", "目标岗位要求并发接口经验"));
        when(profiles.get("user")).thenReturn(Map.of("profileVersion", 1));
        when(mapper.activeSession("user", itemId)).thenReturn(null);
        service.createSession("user", "/sessions", "key", body);
        var payload = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(tasks).create(eq("user"), eq("POST"), eq("/sessions"), eq("key"), eq(body),
                eq("LEARNING_QUESTION"), eq("LEARNING_SESSION"), anyString(), any(), payload.capture(), any());
        Map<?, ?> item = (Map<?, ?>) payload.getValue().get("planItem");
        org.junit.jupiter.api.Assertions.assertEquals("修复重复提交", item.get("objective"));
        org.junit.jupiter.api.Assertions.assertEquals("说明幂等键和并发测试", item.get("completionCriteria"));
        org.junit.jupiter.api.Assertions.assertEquals("目标岗位要求并发接口经验", item.get("reason"));
    }

    @Test
    void failedEvaluationRetryReusesAnswerAndOriginalContent() {
        LearningMapper mapper = mock(LearningMapper.class);
        TaskMapper taskMapper = mock(TaskMapper.class);
        TaskService tasks = mock(TaskService.class);
        JsonService json = new JsonService(JsonMapper.builder().build());
        LearningService service = new LearningService(mapper, mock(JobMapper.class), mock(JobService.class),
                mock(ProfileService.class), mock(ResumeMapper.class), tasks, taskMapper, json, new ApiView(json));
        Map<String, Object> body = Map.of("content", "原回答", "expectedVersion", 2);
        when(mapper.lockSession("session", "user")).thenReturn(Map.of("id", "session", "version", 2, "status", "ACTIVE"));
        when(mapper.sessionAnswer("session")).thenReturn(Map.of("id", "answer", "status", "FAILED",
                "content", "原回答", "task_id", "original-task"));
        AiTask original = new AiTask();
        original.setId("original-task");
        original.setInputVersionsJson(json.stringify(Map.of("questionVersion", 1)));
        original.setInputSnapshotJson(json.stringify(Map.of("answerId", "answer", "answer", "原回答")));
        when(taskMapper.latestResourceTask("LEARNING_ANSWER", "answer")).thenReturn(original);

        service.answer("user", "session", "/learning/sessions/session/answers", "key", body);

        verify(tasks).create(eq("user"), eq("POST"), eq("/learning/sessions/session/answers"), eq("key"), eq(body),
                eq("LEARNING_EVALUATE"), eq("LEARNING_ANSWER"), eq("answer"), any(), any(), eq("original-task"), any());
        verify(mapper, never()).insertAnswer(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }
}
