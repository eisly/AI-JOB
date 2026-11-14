package com.aijob.backend.learning;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.http.ResponseEntity;

import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.job.JobMapper;
import com.aijob.backend.job.JobService;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.resume.ResumeMapper;
import com.aijob.backend.task.TaskMapper;
import com.aijob.backend.task.TaskService;

import tools.jackson.databind.json.JsonMapper;

class LearningServiceResumePlanTest {

    private final JsonService json = new JsonService(JsonMapper.builder().build());

    private LearningService service(LearningMapper mapper, ProfileService profiles, ResumeMapper resumes,
                                    TaskService tasks) {
        return new LearningService(mapper, mock(JobMapper.class), mock(JobService.class), profiles, resumes,
                tasks, mock(TaskMapper.class), json, new ApiView(json));
    }

    @Test
    void requiresResumeWhenUserHasNoParsedVersion() {
        LearningMapper mapper = mock(LearningMapper.class);
        ProfileService profiles = mock(ProfileService.class);
        ResumeMapper resumes = mock(ResumeMapper.class);
        TaskService tasks = mock(TaskService.class);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("profileVersion", 3);
        profile.put("targetRole", "JAVA_BACKEND");
        profile.put("activeResumeVersionId", null);
        when(profiles.get("user")).thenReturn(profile);
        when(profiles.roles()).thenReturn(Set.of("JAVA_BACKEND"));
        when(resumes.latestVersions("user", 20)).thenReturn(List.of());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service(mapper, profiles, resumes, tasks).createPlanFromResume("user",
                        "/api/v1/learning/plans/from-resume", "key",
                        Map.of("dailyMinutes", 30, "days", 7)));

        assertEquals(409, error.getHttpStatus());
        assertEquals("RESUME_REQUIRED", error.getErrorKey());
        assertEquals("GO_RESUME", error.getDetails().get("action"));
    }

    @Test
    void buildsResumePayloadWithHistoryAndLineage() {
        LearningMapper mapper = mock(LearningMapper.class);
        ProfileService profiles = mock(ProfileService.class);
        ResumeMapper resumes = mock(ResumeMapper.class);
        TaskService tasks = mock(TaskService.class);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("profileVersion", 3);
        profile.put("targetRole", "JAVA_BACKEND");
        profile.put("activeResumeVersionId", "version-1");
        when(profiles.get("user")).thenReturn(profile);
        when(profiles.roles()).thenReturn(Set.of("JAVA_BACKEND"));
        when(profiles.agentSkillCatalog("user")).thenReturn(List.of(
                Map.of("code", "JAVA", "name", "Java"),
                Map.of("code", "SPRING_BOOT", "name", "Spring Boot")));
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("education", "BACHELOR");
        facts.put("experienceMonths", 12);
        facts.put("major", "计算机");
        facts.put("skills", List.of(Map.of("skillCode", "JAVA", "claim", "负责订单系统",
                "quote", "基于 Spring Boot 实现订单系统", "location", "项目经历")));
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("version_id", "version-1");
        version.put("version_no", 2);
        version.put("title", "后端简历");
        version.put("status", "PARSED");
        version.put("parsed_facts_json", json.stringify(facts));
        version.put("confirmed_facts_json", null);
        version.put("extracted_text", "项目经历：基于 Spring Boot 实现订单系统，使用 Redis 做缓存。");
        when(resumes.versionWithResume("version-1", "user")).thenReturn(version);
        when(mapper.openMistakeSkillJson("user")).thenReturn(List.of("[\"SPRING_BOOT\"]"));
        when(tasks.create(anyString(), anyString(), anyString(), anyString(), any(), anyString(), anyString(),
                anyString(), any(), any(), any())).thenAnswer(invocation -> {
                    Consumer<String> placeholder = invocation.getArgument(10);
                    placeholder.accept("task-1");
                    return ResponseEntity.ok(null);
                });

        service(mapper, profiles, resumes, tasks).createPlanFromResume("user",
                "/api/v1/learning/plans/from-resume", "key",
                Map.of("dailyMinutes", 30, "days", 7, "targetDirection", "JAVA_BACKEND"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(tasks).create(eq("user"), eq("POST"), eq("/api/v1/learning/plans/from-resume"), eq("key"), any(),
                eq("LEARNING_PLAN"), eq("LEARNING_PLAN"), anyString(), any(), payload.capture(), any());
        assertEquals("RESUME", payload.getValue().get("planSource"));
        assertEquals("JAVA_BACKEND", payload.getValue().get("targetDirection"));
        assertTrue(((List<?>) payload.getValue().get("allowedSkillCodes")).contains("JAVA"));
        @SuppressWarnings("unchecked")
        Map<String, Object> history = (Map<String, Object>) payload.getValue().get("trainingHistory");
        assertEquals(0, history.get("evaluatedAnswers"));
        assertEquals(List.of("SPRING_BOOT"), history.get("weakSkillCodes"));

        verify(mapper).insertPlan(anyString(), eq("user"), nullable(String.class), eq("RESUME"), eq("version-1"),
                eq("JAVA_BACKEND"), nullable(String.class), eq(1), anyString(), eq("task-1"), eq(3L), eq(30), eq(7));
    }
}
