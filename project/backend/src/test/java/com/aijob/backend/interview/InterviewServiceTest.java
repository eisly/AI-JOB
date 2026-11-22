package com.aijob.backend.interview;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aijob.backend.ability.AbilityService;
import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.job.JobMapper;
import com.aijob.backend.learning.LearningMapper;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.resume.ResumeMapper;
import com.aijob.backend.task.AiTask;
import com.aijob.backend.task.TaskMapper;
import com.aijob.backend.task.TaskService;

import tools.jackson.databind.json.JsonMapper;

class InterviewServiceTest {

    @Test
    void agentMessagesPreserveQuestionSkillsForAnswerEvidence() {
        InterviewMapper mapper = mock(InterviewMapper.class);
        JsonService json = new JsonService(JsonMapper.builder().build());
        InterviewService service = new InterviewService(mapper, mock(JobMapper.class), mock(ResumeMapper.class),
                mock(LearningMapper.class), mock(ProfileService.class), mock(AbilityService.class),
                mock(TaskService.class), mock(TaskMapper.class), json, new ApiView(json),
                mock(com.aijob.backend.interview.voice.VoiceMapper.class));
        when(mapper.messages("interview")).thenReturn(List.of(Map.of(
                "id", "question", "seq", 1, "role", "ASSISTANT", "content", "Explain transactions",
                "question_no", 1, "follow_up_no", 0, "skill_codes_json", "[\"JAVA\",\"MYSQL\"]")));

        assertEquals(List.of("JAVA", "MYSQL"), service.agentMessages("interview").getFirst().get("skillCodes"));
    }

    @Test
    void createPersistsVoiceModeAndDefaultsToText() {
        InterviewMapper mapper = mock(InterviewMapper.class);
        JobMapper jobs = mock(JobMapper.class);
        ResumeMapper resumes = mock(ResumeMapper.class);
        ProfileService profiles = mock(ProfileService.class);
        TaskService tasks = mock(TaskService.class);
        JsonService json = new JsonService(JsonMapper.builder().build());
        InterviewService service = new InterviewService(mapper, jobs, resumes, mock(LearningMapper.class), profiles,
                mock(AbilityService.class), tasks, mock(TaskMapper.class), json, new ApiView(json),
                mock(com.aijob.backend.interview.voice.VoiceMapper.class));
        String userId = "user";
        String jobId = UUID.randomUUID().toString();
        String resumeId = UUID.randomUUID().toString();
        when(mapper.activeCount(userId)).thenReturn(0);
        when(jobs.job(jobId, userId)).thenReturn(Map.of("status", "ACTIVE", "job_version", 1, "jd_text", "JD"));
        when(resumes.version(resumeId, userId)).thenReturn(Map.of("status", "CONFIRMED"));
        when(profiles.get(userId)).thenReturn(Map.of("profileVersion", 1L));

        service.create(userId, "/interviews", "key",
                Map.of("jobId", jobId, "resumeVersionId", resumeId, "questionLimit", 5, "mode", "VOICE"));

        ArgumentCaptor<Consumer<String>> placeholder = ArgumentCaptor.forClass(Consumer.class);
        verify(tasks).create(eq(userId), eq("POST"), eq("/interviews"), eq("key"), any(), eq("INTERVIEW_TURN"),
                eq("INTERVIEW"), anyString(), any(), any(), placeholder.capture());
        placeholder.getValue().accept("task-1");
        verify(mapper).insertSession(anyString(), eq(userId), eq(jobId), eq(1), eq(resumeId), eq(1L), anyString(),
                eq("VOICE"), eq(5), eq("task-1"), isNull());
    }

    @Test
    void createRejectsUnknownModeBeforeCreatingTask() {
        InterviewMapper mapper = mock(InterviewMapper.class);
        TaskService tasks = mock(TaskService.class);
        JsonService json = new JsonService(JsonMapper.builder().build());
        InterviewService service = new InterviewService(mapper, mock(JobMapper.class), mock(ResumeMapper.class),
                mock(LearningMapper.class), mock(ProfileService.class), mock(AbilityService.class), tasks,
                mock(TaskMapper.class), json, new ApiView(json),
                mock(com.aijob.backend.interview.voice.VoiceMapper.class));
        when(mapper.activeCount("user")).thenReturn(0);

        assertThrows(BusinessException.class, () -> service.create("user", "/interviews", "key",
                Map.of("jobId", UUID.randomUUID().toString(), "resumeVersionId", UUID.randomUUID().toString(),
                        "questionLimit", 5, "mode", "AUDIO")));
        verify(tasks, never()).create(anyString(), anyString(), anyString(), anyString(), any(), anyString(),
                anyString(), anyString(), any(), any(), any(Consumer.class));
    }

    @Test
    void cancelledTurnRetryReusesOriginalUserMessageAndContent() {
        InterviewMapper mapper = mock(InterviewMapper.class);
        TaskMapper taskMapper = mock(TaskMapper.class);
        TaskService tasks = mock(TaskService.class);
        JsonService json = new JsonService(JsonMapper.builder().build());
        InterviewService service = new InterviewService(mapper, mock(JobMapper.class), mock(ResumeMapper.class),
                mock(LearningMapper.class), mock(ProfileService.class), mock(AbilityService.class), tasks, taskMapper,
                json, new ApiView(json), mock(com.aijob.backend.interview.voice.VoiceMapper.class));
        String userId = "user";
        String interviewId = "interview";
        String questionId = "60000000-0000-4000-8000-000000000002";
        String originalTaskId = "original-task";
        Map<String, Object> body = Map.of("questionMessageId", questionId, "content", "原始回答", "expectedVersion", 3);
        Map<String, Object> frozen = Map.of("profile", Map.of("profileVersion", 1),
                "job", Map.of("id", "job", "jobVersion", 1, "jdText", "frozen"));
        when(mapper.lockSession(interviewId, userId)).thenReturn(Map.of("id", interviewId, "version", 3,
                "status", "WAITING_ANSWER", "input_snapshot_json", json.stringify(frozen)));
        when(mapper.latestQuestion(interviewId)).thenReturn(Map.of("id", questionId, "question_no", 1, "follow_up_no", 0));
        when(mapper.answerByQuestion(questionId, interviewId)).thenReturn(Map.of("id", "answer", "content", "原始回答"));
        AiTask original = new AiTask();
        original.setId(originalTaskId);
        original.setStatus("CANCELLED");
        original.setInputVersionsJson(json.stringify(Map.of("interviewVersion", 2, "profileVersion", 1)));
        original.setInputSnapshotJson(json.stringify(Map.of("expectedVersion", 2, "messages", List.of(),
                "latestAnswer", Map.of("messageId", "answer", "questionMessageId", questionId, "content", "原始回答"))));
        when(taskMapper.latestResourceTask("INTERVIEW", interviewId)).thenReturn(original);
        when(mapper.messages(interviewId)).thenReturn(List.of());

        service.answer(userId, interviewId, "/interviews/" + interviewId + "/answers", "key", body);

        ArgumentCaptor<Map> payload = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map> versions = ArgumentCaptor.forClass(Map.class);
        verify(tasks).create(eq(userId), eq("POST"), eq("/interviews/" + interviewId + "/answers"), eq("key"),
                eq(body), eq("INTERVIEW_TURN"), eq("INTERVIEW"), eq(interviewId), versions.capture(), payload.capture(),
                eq(originalTaskId), any());
        Map<String, Object> latestAnswer = (Map<String, Object>) payload.getValue().get("latestAnswer");
        assertEquals("原始回答", latestAnswer.get("content"));
        assertEquals("answer", latestAnswer.get("messageId"));
        assertEquals(3, payload.getValue().get("expectedVersion"));
        assertEquals(3, versions.getValue().get("interviewVersion"));
        verify(mapper, never()).insertMessage(anyString(), anyString(), anyInt(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), any(), anyString(), anyString());
    }
}
