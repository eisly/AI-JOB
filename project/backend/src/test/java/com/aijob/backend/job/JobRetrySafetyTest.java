package com.aijob.backend.job;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.task.AiTask;
import tools.jackson.databind.json.JsonMapper;

class JobRetrySafetyTest {
    @Test
    void retryCannotOverwriteManuallyCorrectedJdWithOldSnapshot() {
        var mapper = mock(JobMapper.class);
        var json = new JsonService(JsonMapper.builder().build());
        var handler = new JobResultHandler(mapper, null, json, null, null, null, null);
        var task = new AiTask();
        task.setOperation("JD_ANALYZE"); task.setResourceId("job"); task.setUserId("user");
        task.setInputVersionsJson("{\"jobVersion\":1}");
        when(mapper.lockJob("job", "user")).thenReturn(Map.of("job_version", 2, "status", "ACTIVE"));
        var error = assertThrows(BusinessException.class, () -> handler.prepareRetry(task, "new"));
        assertEquals("STALE_INPUT", error.getErrorKey());
        verify(mapper, never()).updateJobStatus(anyString(), anyString());
    }
}
