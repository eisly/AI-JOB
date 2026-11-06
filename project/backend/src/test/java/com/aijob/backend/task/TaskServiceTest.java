package com.aijob.backend.task;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
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
}
