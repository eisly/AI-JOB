package com.aijob.backend.task;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.interview.InterviewMapper;

class TaskRetryServiceTest {
    @Test
    void oldFailureCannotCreateAnotherRetryWhileReplacementRunsOrHasSucceeded() {
        TaskMapper mapper = mock(TaskMapper.class);
        TaskService tasks = mock(TaskService.class);
        AiTask original = new AiTask();
        original.setId("old"); original.setStatus("FAILED"); original.setResourceType("JOB"); original.setResourceId("job");
        AiTask replacement = new AiTask(); replacement.setId("new");
        when(mapper.lockOwned("old", "user")).thenReturn(original);
        when(mapper.latestResourceTask("JOB", "job")).thenReturn(replacement);
        TaskRetryService service = new TaskRetryService(mapper, tasks, mock(JsonService.class), List.of(), mock(InterviewMapper.class));
        for (String status : List.of("QUEUED", "RUNNING", "CANCEL_REQUESTED", "SUCCEEDED")) {
            replacement.setStatus(status);
            assertThrows(BusinessException.class, () -> service.retry("user", "old", "key", "route"));
        }
        verify(mapper, times(4)).lockUser("user");
    }
}
