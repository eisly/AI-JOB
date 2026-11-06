package com.aijob.backend.task;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.CommonResult;
import com.aijob.backend.common.Data;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.common.PageResult;

@Service
public class TaskService {

    private static final Set<String> STATUSES = Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED",
            "CANCEL_REQUESTED", "CANCELLED", "EXPIRED");
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED");

    private final TaskMapper mapper;
    private final JsonService json;
    private final IdempotencyService idempotency;
    private final long deadlineSeconds;

    public TaskService(TaskMapper mapper, JsonService json, IdempotencyService idempotency,
                       @Value("${app.tasks.deadline-seconds}") long deadlineSeconds) {
        this.mapper = mapper;
        this.json = json;
        this.idempotency = idempotency;
        this.deadlineSeconds = deadlineSeconds;
    }

    public ResponseEntity<CommonResult<Object>> replay(String userId, String method, String route, String key,
                                                        Object request) {
        return idempotency.replayTask(userId, method, route, key, request);
    }

    public ResponseEntity<CommonResult<Object>> create(String userId, String method, String route, String key,
                                                       Object request, String operation, String resourceType,
                                                       String resourceId, Map<String, Object> inputVersions,
                                                       Map<String, Object> payload, Consumer<String> placeholder) {
        return create(userId, method, route, key, request, operation, resourceType, resourceId,
                inputVersions, payload, null, placeholder);
    }

    public ResponseEntity<CommonResult<Object>> create(String userId, String method, String route, String key,
                                                       Object request, String operation, String resourceType,
                                                       String resourceId, Map<String, Object> inputVersions,
                                                       Map<String, Object> payload, String retryOfTaskId,
                                                       Consumer<String> placeholder) {
        return idempotency.executeTask(userId, method, route, key, request, () -> {
            mapper.lockUser(userId);
            int activeCount = mapper.activeTaskCount(userId);
            // The final interview turn creates its report inside the same result transaction.
            if ("INTERVIEW_REPORT".equals(operation)) {
                AiTask turn = mapper.latestResourceTask("INTERVIEW", resourceId);
                if (turn != null && userId.equals(turn.getUserId()) && "RUNNING".equals(turn.getStatus())) {
                    activeCount--;
                }
            }
            if (activeCount >= 2) {
                throw BusinessException.conflict("每位用户最多同时执行两个 AI 任务", "INVALID_STATE");
            }
            String taskId = UUID.randomUUID().toString();
            AiTask task = new AiTask();
            task.setId(taskId);
            task.setUserId(userId);
            task.setOperation(operation);
            task.setResourceType(resourceType);
            task.setResourceId(resourceId);
            task.setIdempotencyKey(key);
            task.setInputVersionsJson(json.stringify(inputVersions));
            task.setInputSnapshotJson(json.stringify(payload));
            task.setRetryOfTaskId(retryOfTaskId);
            task.setDeadlineAt(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(deadlineSeconds));
            mapper.insertTask(task);
            placeholder.accept(taskId);
            appendEvent(taskId, 1, "accepted", Map.of("type", operation, "resourceId", resourceId));
            mapper.insertOutbox(UUID.randomUUID().toString(), "AI_DISPATCH", taskId,
                    "AI_DISPATCH:" + taskId, json.stringify(Map.of("taskId", taskId)));
            AiTask saved = mapper.find(taskId);
            return new IdempotencyService.ActionResult(taskId, vo(saved));
        });
    }

    @Transactional
    public Map<String, Object> cancel(String userId, String taskId) {
        AiTask task = ownedLocked(userId, taskId);
        if (TERMINAL.contains(task.getStatus()) || "CANCEL_REQUESTED".equals(task.getStatus())) {
            return vo(task);
        }
        if (mapper.transition(taskId, task.getStatus(), "CANCEL_REQUESTED") == 0) {
            throw BusinessException.conflict("任务状态已变化", "INVALID_STATE");
        }
        mapper.insertOutbox(UUID.randomUUID().toString(), "AI_CANCEL", taskId,
                "AI_CANCEL:" + taskId, json.stringify(Map.of("taskId", taskId)));
        return vo(mapper.find(taskId));
    }

    public Map<String, Object> detail(String userId, String taskId) {
        AiTask task = mapper.findOwned(taskId, userId);
        if (task == null) {
            throw BusinessException.notFound();
        }
        return vo(task);
    }

    public PageResult<Map<String, Object>> list(String userId, String status, Integer current, Integer size) {
        int page = Data.page(current);
        int pageSize = Data.size(size);
        long offset = Data.offset(current, size);
        List<String> statuses = new ArrayList<>();
        if (status != null) {
            Arrays.stream(status.split(",")).map(String::trim).filter(value -> !value.isEmpty()).forEach(value -> {
                if (!STATUSES.contains(value)) {
                    throw BusinessException.validation("status", "非法任务状态: " + value);
                }
                statuses.add(value);
            });
        }
        List<Map<String, Object>> records = mapper.page(userId, statuses, pageSize, offset).stream().map(this::vo).toList();
        return new PageResult<>(mapper.count(userId, statuses), records, page, pageSize);
    }

    public AiTask ownedLocked(String userId, String taskId) {
        AiTask task = mapper.lockOwned(taskId, userId);
        if (task == null) {
            throw BusinessException.notFound();
        }
        return task;
    }

    public Map<String, Object> vo(AiTask task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", task.getId());
        result.put("type", task.getOperation());
        result.put("status", task.getStatus());
        result.put("stage", task.getStage());
        result.put("progress", task.getProgress());
        result.put("resourceId", task.getResourceId());
        result.put("result", task.getResultRefJson() == null ? null : json.convert(json.parse(task.getResultRefJson()), Object.class));
        result.put("error", task.getErrorJson() == null ? null : json.convert(json.parse(task.getErrorJson()), Object.class));
        result.put("inputVersions", json.map(task.getInputVersionsJson()));
        result.put("lastEventId", task.getLastEventSeq());
        result.put("createdAt", task.getCreatedAt() == null ? null : task.getCreatedAt().toInstant(java.time.ZoneOffset.UTC));
        result.put("finishedAt", task.getFinishedAt() == null ? null : task.getFinishedAt().toInstant(java.time.ZoneOffset.UTC));
        return result;
    }

    public void appendEvent(AiTask task, String type, Object payload) {
        long seq = task.getLastEventSeq() + 1;
        appendEvent(task.getId(), seq, type, payload);
        mapper.updateLastEvent(task.getId(), seq);
        task.setLastEventSeq(seq);
    }

    public boolean terminal(String status) {
        return TERMINAL.contains(status);
    }

    private void appendEvent(String taskId, long seq, String type, Object payload) {
        mapper.insertEvent(UUID.randomUUID().toString(), taskId, seq, type, json.stringify(payload),
                LocalDateTime.now(ZoneOffset.UTC).plusHours(24));
    }
}
