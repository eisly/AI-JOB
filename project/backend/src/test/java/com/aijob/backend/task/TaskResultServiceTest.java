package com.aijob.backend.task;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aijob.backend.common.JsonService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TaskResultServiceTest {

    @Test
    void cancelWorkerCannotCancelATaskWithoutCancellationRequest() {
        TaskMapper mapper = mock(TaskMapper.class);
        AiTask task = new AiTask();
        task.setStatus("RUNNING");
        when(mapper.lock("task")).thenReturn(task);
        TaskResultService service = new TaskResultService(mapper, mock(TaskService.class),
                new JsonService(JsonMapper.builder().build()), List.of());

        service.completeCancellation("task");

        verify(mapper, never()).finishFailure(any(), any(), any(), any());
    }

    @Test
    void cancelRequestedRejectsLateSuccessWithoutBusinessSideEffects() {
        assertLateResultRejected("CANCEL_REQUESTED");
    }

    @Test
    void terminalTaskRejectsLateSuccessWithoutBusinessSideEffects() {
        assertLateResultRejected("CANCELLED");
    }

    private void assertLateResultRejected(String taskStatus) {
        TaskMapper mapper = mock(TaskMapper.class);
        TaskService tasks = mock(TaskService.class);
        JsonService json = new JsonService(JsonMapper.builder().build());
        TaskResultHandler handler = mock(TaskResultHandler.class);
        when(handler.operations()).thenReturn(java.util.Set.of("RESUME_DIAGNOSE"));
        when(tasks.terminal(taskStatus)).thenReturn(!"CANCEL_REQUESTED".equals(taskStatus));
        AiTask task = new AiTask();
        task.setId("task");
        task.setUserId("user");
        task.setOperation("RESUME_DIAGNOSE");
        task.setStatus(taskStatus);
        when(mapper.lock("task")).thenReturn(task);
        TaskResultService service = new TaskResultService(mapper, tasks, json, List.of(handler));
        Map<String, Object> callback = Map.of("runId", java.util.UUID.randomUUID().toString(),
                "operation", "RESUME_DIAGNOSE", "status", "SUCCEEDED", "inputVersions", Map.of(),
                "result", Map.of("items", List.of()), "error", Map.of());

        Map<String, Object> response = service.submit("task", callback);

        assertEquals(false, response.get("accepted"));
        assertEquals(taskStatus, response.get("status"));
        verify(handler, never()).success(any(AiTask.class), any(JsonNode.class));
        verify(handler, never()).failure(any(AiTask.class), any(JsonNode.class));
        verify(mapper, never()).succeed(any(), any(), any());
    }
}
