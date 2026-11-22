package com.aijob.backend.task;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.aijob.backend.common.JsonService;
import tools.jackson.databind.json.JsonMapper;

class TaskTraceabilityTest {
    @Test
    void responseRetainsRetryParentAndOriginalInputVersions() {
        JsonService json = new JsonService(JsonMapper.builder().build());
        TaskService service = new TaskService(mock(TaskMapper.class), json, null, 300);
        AiTask task = new AiTask();
        task.setId("replacement");
        task.setRetryOfTaskId("original");
        task.setInputVersionsJson("{\"profileVersion\":3}");
        Map<String, Object> response = service.vo(task);
        assertEquals("original", response.get("retryOfTaskId"));
        assertEquals(Map.of("profileVersion", 3), response.get("inputVersions"));
    }
}
