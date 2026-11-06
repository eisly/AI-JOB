package com.aijob.backend.task;

import static org.mockito.Mockito.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OutboxSchedulerTest {
    @Test
    void queuedCancellationCompletesEvenWhenAgentRunDoesNotExist() {
        OutboxService outbox = mock(OutboxService.class);
        AgentClient agent = mock(AgentClient.class);
        TaskMapper mapper = mock(TaskMapper.class);
        TaskResultService results = mock(TaskResultService.class);
        AiTask task = new AiTask();
        task.setId("task");
        task.setStatus("CANCEL_REQUESTED");
        when(outbox.claim("AI_CANCEL")).thenReturn(Map.of("id", "event", "lease_owner", "owner", "aggregate_id", "task"))
                .thenReturn(null);
        when(mapper.find("task")).thenReturn(task);
        when(agent.findByTask("task")).thenThrow(new IllegalStateException("run not created"));

        new OutboxScheduler(outbox, agent, mapper, results, true).cancel();

        verify(results).completeCancellation("task");
        verify(outbox).fail("event", "owner", "IllegalStateException");
    }
}
