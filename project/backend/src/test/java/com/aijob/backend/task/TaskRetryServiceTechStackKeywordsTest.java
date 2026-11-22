package com.aijob.backend.task;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aijob.backend.common.JsonService;
import com.aijob.backend.interview.InterviewMapper;

import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class TaskRetryServiceTechStackKeywordsTest {

    private final JsonService json = new JsonService(JsonMapper.builder().build());
    private final TaskMapper mapper = mock(TaskMapper.class);
    private final TaskService taskService = mock(TaskService.class);
    private final InterviewMapper interviews = mock(InterviewMapper.class);
    private final TaskResultHandler handler = mock(TaskResultHandler.class);

    @Test
    void retryReusesTechStackKeywordsFromOriginalSnapshot() {
        AiTask original = new AiTask();
        original.setId("task-1");
        original.setUserId("user");
        original.setStatus("FAILED");
        original.setOperation("PROJECT_RECOMMEND");
        original.setResourceType("PROJECT_RECOMMENDATION");
        original.setResourceId("recommendation-1");
        original.setInputVersionsJson(json.stringify(Map.of("profileVersion", 3)));
        original.setInputSnapshotJson(json.stringify(Map.of(
                "allowedSkillCodes", List.of("JAVA"),
                "techStackKeywords", List.of("LangGraph", "FastAPI"),
                "maxDurationWeeks", 4)));
        when(mapper.lockOwned("task-1", "user")).thenReturn(original);
        when(handler.operations()).thenReturn(Set.of("PROJECT_RECOMMEND"));

        new TaskRetryService(mapper, taskService, json, List.of(handler), interviews)
                .retry("user", "task-1", "key", "/api/v1/tasks/task-1/retry");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(taskService).create(eq("user"), eq("POST"), eq("/api/v1/tasks/task-1/retry"), eq("key"), any(),
                eq("PROJECT_RECOMMEND"), eq("PROJECT_RECOMMENDATION"), eq("recommendation-1"),
                anyMap(), payload.capture(), eq("task-1"), any());
        assertEquals(List.of("LangGraph", "FastAPI"), payload.getValue().get("techStackKeywords"));
    }
}