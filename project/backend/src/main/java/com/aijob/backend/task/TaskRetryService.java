package com.aijob.backend.task;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.CommonResult;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.interview.InterviewMapper;

@Service
public class TaskRetryService {

    private final TaskMapper mapper;
    private final TaskService taskService;
    private final JsonService json;
    private final List<TaskResultHandler> handlers;
    private final InterviewMapper interviews;

    public TaskRetryService(TaskMapper mapper, TaskService taskService, JsonService json,
                            List<TaskResultHandler> handlers, InterviewMapper interviews) {
        this.mapper = mapper;
        this.taskService = taskService;
        this.json = json;
        this.handlers = handlers;
        this.interviews = interviews;
    }

    @Transactional
    public ResponseEntity<CommonResult<Object>> retry(String userId, String taskId, String key, String route) {
        AiTask original = mapper.lockOwned(taskId, userId);
        if (original == null) {
            throw BusinessException.notFound();
        }
        Map<String, Object> request = Map.of("retryOfTaskId", taskId);
        ResponseEntity<CommonResult<Object>> replay = taskService.replay(userId, "POST", route, key, request);
        if (replay != null) return replay;
        mapper.lockUser(userId);
        AiTask latest = mapper.latestResourceTask(original.getResourceType(), original.getResourceId());
        if (latest != null && !taskId.equals(latest.getId())) {
            if (Set.of("QUEUED", "RUNNING", "CANCEL_REQUESTED").contains(latest.getStatus())) {
                throw BusinessException.taskInProgress(latest.getId());
            }
            if ("SUCCEEDED".equals(latest.getStatus())) {
                throw BusinessException.conflict("该资源已成功处理，不能重试旧失败任务", "INVALID_STATE");
            }
        }
        Map<String, Object> payload = json.map(original.getInputSnapshotJson());
        boolean discoveryContinuation = "PROJECT_RECOMMEND".equals(original.getOperation())
                && payload.get("discoveryRound") instanceof Number page && page.intValue() > 0;
        boolean retryCancelled = "CANCELLED".equals(original.getStatus())
                && (discoveryContinuation || Set.of("INTERVIEW_TURN", "LEARNING_EVALUATE", "LEARNING_PRACTICE_EVALUATE").contains(original.getOperation()));
        if (!Set.of("FAILED", "EXPIRED").contains(original.getStatus()) && !retryCancelled) {
            throw BusinessException.conflict("仅失败、过期或允许重试的已取消任务可以重试", "INVALID_STATE");
        }
        TaskResultHandler handler = handlers.stream()
                .filter(item -> item.operations().contains(original.getOperation())).findFirst()
                .orElseThrow(() -> BusinessException.conflict("该任务类型不支持通用重试", "INVALID_STATE"));
        Map<String, Object> versions = json.map(original.getInputVersionsJson());
        if ("INTERVIEW_TURN".equals(original.getOperation())) {
            Map<String, Object> session = interviews.lockSession(original.getResourceId(), userId);
            if (session == null) {
                throw BusinessException.notFound();
            }
            int expected = ((Number) session.get("version")).intValue();
            versions.put("interviewVersion", expected);
            payload.put("expectedVersion", expected);
        }
        return taskService.create(userId, "POST", route, key, request, original.getOperation(),
                original.getResourceType(), original.getResourceId(), versions, payload, taskId,
                newTaskId -> handler.prepareRetry(original, newTaskId));
    }
}
