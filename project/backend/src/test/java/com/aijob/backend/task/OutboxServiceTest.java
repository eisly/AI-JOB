package com.aijob.backend.task;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;

class OutboxServiceTest {

    private final TaskMapper mapper = mock(TaskMapper.class);
    private final OutboxService service = new OutboxService(mapper);

    @Test
    void staleLeaseOwnerCannotBindRunOrCompleteOutbox() {
        when(mapper.doneOutbox("outbox", "old-owner")).thenReturn(0);

        service.completeDispatch("outbox", "old-owner", "task", "run");

        verify(mapper, never()).lock(any());
        verify(mapper, never()).bindRun(any(), any());
    }

    @Test
    void staleLeaseOwnerCannotRecordFailureOverNewWorker() {
        when(mapper.outbox("outbox")).thenReturn(java.util.Map.of("attempts", 1));
        when(mapper.retryOutbox(any(), any(), any(), any(), any())).thenReturn(0);

        int attempts = service.fail("outbox", "old-owner", "timeout");

        org.junit.jupiter.api.Assertions.assertEquals(-1, attempts);
    }
}