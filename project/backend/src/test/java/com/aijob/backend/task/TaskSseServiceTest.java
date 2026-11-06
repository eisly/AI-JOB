package com.aijob.backend.task;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;

class TaskSseServiceTest {
    @Test
    void timeoutAndCompletionReleaseOnlyOneConnection() {
        TaskMapper mapper = mock(TaskMapper.class);
        TaskService tasks = mock(TaskService.class);
        AiTask task = new AiTask();
        task.setLastEventSeq(0L);
        when(mapper.findOwned("task", "user")).thenReturn(task);
        when(mapper.eventBounds("task")).thenReturn(Map.of("total", 0));
        TaskSseService service = new TaskSseService(mapper, tasks, mock(JsonService.class), 15);

        SseEmitter first = service.subscribe("user", "task", null);
        service.subscribe("user", "task", null);
        service.subscribe("user", "task", null);
        assertThrows(BusinessException.class, () -> service.subscribe("user", "task", null));

        ((Runnable) ReflectionTestUtils.getField(first, "timeoutCallback")).run();
        ((Runnable) ReflectionTestUtils.getField(first, "completionCallback")).run();
        service.subscribe("user", "task", null);
        assertThrows(BusinessException.class, () -> service.subscribe("user", "task", null));
    }
}
