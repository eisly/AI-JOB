package com.aijob.backend.task;

import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;

import tools.jackson.databind.json.JsonMapper;

class TaskServiceTest {
    @Test
    void reportReplacesItsCompletingTurnInConcurrencyLimit() {
        TaskMapper mapper = mock(TaskMapper.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(idempotency.executeTask(any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> call.getArgument(5, Supplier.class).get());
        when(mapper.activeTaskCount("user")).thenReturn(2);
        AiTask turn = new AiTask();
        turn.setUserId("user");
        turn.setStatus("RUNNING");
        when(mapper.latestResourceTask("INTERVIEW", "interview")).thenReturn(turn);
        TaskService service = new TaskService(mapper, new JsonService(JsonMapper.builder().build()), idempotency, 300);

        IllegalStateException marker = new IllegalStateException("placeholder reached");
        assertSame(marker, assertThrows(IllegalStateException.class, () -> service.create("user", "POST", "route", "key",
                Map.of(), "INTERVIEW_REPORT", "INTERVIEW_REPORT", "interview", Map.of(), Map.of(), id -> { throw marker; })));
        turn.setStatus("SUCCEEDED");
        assertThrows(BusinessException.class, () -> service.create("user", "POST", "route", "key", Map.of(),
                "INTERVIEW_REPORT", "INTERVIEW_REPORT", "interview", Map.of(), Map.of(), id -> {}));
    }

    @Test
    void appendEventUsesDatabaseSequenceAfterAgentEventSync() {
        TaskMapper mapper = mock(TaskMapper.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        TaskService service = new TaskService(mapper, new JsonService(JsonMapper.builder().build()), idempotency, 300);
        // 场景：回调终态前 syncTask 已写入 Agent 事件（DB MAX(seq)=5），
        // 但回调线程持有的内存 task 快照仍是 lastEventSeq=3。
        // appendEvent 必须以 DB MAX+1=6 分配，而不是旧快照 +1=4，否则触发 uk_task_id_seq 冲突。
        when(mapper.nextEventSeq("task")).thenReturn(6L);
        AiTask task = new AiTask();
        task.setId("task");
        task.setLastEventSeq(3L);

        service.appendEvent(task, "done", Map.of("version", 1));

        verify(mapper).insertEvent(any(), eq("task"), eq(6L), eq("done"), any(), any());
        verify(mapper).updateLastEvent("task", 6L);
        assertEquals(Long.valueOf(6), task.getLastEventSeq());
    }
}
