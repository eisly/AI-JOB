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