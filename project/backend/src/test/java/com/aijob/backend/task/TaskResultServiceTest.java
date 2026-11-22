package com.aijob.backend.task;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aijob.backend.common.BusinessException;
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
                new JsonService(JsonMapper.builder().build()), mock(AgentEventSyncService.class), List.of());

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

    @Test
    void invalidDiagnosisResultFinishesTaskWithoutWaitingForExpiry() {
        TaskMapper mapper = mock(TaskMapper.class);
        TaskService tasks = mock(TaskService.class);
        JsonService json = new JsonService(JsonMapper.builder().build());
        TaskResultHandler handler = mock(TaskResultHandler.class);
        when(handler.operations()).thenReturn(java.util.Set.of("RESUME_DIAGNOSE"));
        AiTask task = new AiTask();
        task.setId("task");
        task.setStatus("RUNNING");
        task.setOperation("RESUME_DIAGNOSE");
        task.setInputVersionsJson("{}");
        task.setAgentRunId(java.util.UUID.randomUUID().toString());
        when(mapper.lock("task")).thenReturn(task);
        when(handler.success(eq(task), any())).thenThrow(BusinessException.aiSchema("诊断引用无效"));
        when(mapper.finishFailure(eq("task"), eq("FAILED"), any(), any())).thenReturn(1);
        TaskResultService service = new TaskResultService(mapper, tasks, json,
                mock(AgentEventSyncService.class), List.of(handler));
        Map<String, Object> callback = Map.of("runId", task.getAgentRunId(), "operation", task.getOperation(),
                "status", "SUCCEEDED", "inputVersions", Map.of(), "result", Map.of("items", List.of()));

        Map<String, Object> response = service.submit("task", callback);

        assertEquals(true, response.get("accepted"));
        assertEquals("FAILED", response.get("status"));
        verify(handler).failure(eq(task), eq(json.tree(Map.of("code", "AI_SCHEMA_INVALID",
                "message", "诊断引用无效", "retryable", true))));
        verify(tasks).appendEvent(eq(task), eq("error"), any());
        verify(mapper, never()).succeed(any(), any(), any());
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
        TaskResultService service = new TaskResultService(mapper, tasks, json,
                mock(AgentEventSyncService.class), List.of(handler));
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
