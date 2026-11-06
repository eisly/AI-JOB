package com.aijob.backend.task;

import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.Data;
import com.aijob.backend.common.JsonService;

import tools.jackson.databind.JsonNode;

@Service
public class TaskResultService {

    private final TaskMapper mapper;
    private final TaskService taskService;
    private final JsonService json;
    private final Map<String, TaskResultHandler> handlers;

    public TaskResultService(TaskMapper mapper, TaskService taskService, JsonService json,
                             List<TaskResultHandler> handlerList) {
        this.mapper = mapper;
        this.taskService = taskService;
        this.json = json;
        this.handlers = new HashMap<>();
        for (TaskResultHandler handler : handlerList) {
            for (String operation : handler.operations()) {
                this.handlers.put(operation, handler);
            }
        }
    }

    @Transactional
    public Map<String, Object> submit(String taskId, Map<String, Object> request) {
        AiTask task = mapper.lock(taskId);
        if (task == null) {
            throw BusinessException.notFound();
        }
        String digest = json.digest(request);
        if (task.getResultDigest() != null
                && HexFormat.of().formatHex(task.getResultDigest()).equals(digest)) {
            return response(true, taskId, task.getStatus());
        }
        if (taskService.terminal(task.getStatus()) || "CANCEL_REQUESTED".equals(task.getStatus())) {
            if (task.getResultDigest() != null && ("SUCCEEDED".equals(task.getStatus()) || "FAILED".equals(task.getStatus()))) {
                throw BusinessException.conflict("同一运行返回了不同结果", "IDEMPOTENCY_CONFLICT");
            }
            return response(false, taskId, task.getStatus());
        }
        String runId = Data.uuid(request, "runId", true);
        String operation = Data.text(request, "operation", true, 32);
        String status = Data.text(request, "status", true, 24);
        if (!Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status)) {
            throw BusinessException.validation("status", "非法回调状态");
        }
        if (!task.getOperation().equals(operation)) {
            throw BusinessException.aiSchema("回调 operation 与任务不一致");
        }
        Map<String, Object> versions = Data.object(request, "inputVersions", true);
        if (!json.canonical(versions).equals(json.canonical(json.map(task.getInputVersionsJson())))) {
            throw BusinessException.aiSchema("回调 inputVersions 与任务固定输入不一致");
        }
        if (task.getAgentRunId() != null && !task.getAgentRunId().equals(runId)) {
            throw BusinessException.conflict("runId 与任务绑定不一致", "INVALID_STATE");
        }
        if (task.getAgentRunId() == null) {
            mapper.bindRun(taskId, runId);
            task.setAgentRunId(runId);
            task.setStatus("RUNNING");
        }
        TaskResultHandler handler = handlers.get(operation);
        if (handler == null) {
            throw BusinessException.aiSchema("未注册的任务结果处理器");
        }
        JsonNode result = json.tree(request.get("result"));
        JsonNode error = json.tree(request.get("error"));
        if ("SUCCEEDED".equals(status)) {
            if (result == null || result.isNull()) {
                throw BusinessException.aiSchema("成功回调缺少 result");
            }
            int version = handler.success(task, result);
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("resourceType", task.getResourceType());
            reference.put("resourceId", task.getResourceId());
            reference.put("version", version);
            if (mapper.succeed(taskId, json.stringify(reference), digest) == 0) {
                throw BusinessException.conflict("任务状态已变化", "INVALID_STATE");
            }
            taskService.appendEvent(task, "done", reference);
            return response(true, taskId, "SUCCEEDED");
        }
        if (result != null && !result.isNull()) {
            throw BusinessException.aiSchema("失败或取消回调不得携带 result");
        }
        Map<String, Object> errorValue = json.map(error);
        if (!(errorValue.get("code") instanceof String)
                || !(errorValue.get("message") instanceof String)
                || !(errorValue.get("retryable") instanceof Boolean)) {
            throw BusinessException.aiSchema("失败或取消回调缺少合法 error");
        }
        handler.failure(task, error);
        String javaStatus = "CANCELLED".equals(status) || "CANCEL_REQUESTED".equals(task.getStatus())
                ? "CANCELLED" : "FAILED";
        finish(task, javaStatus, error, digest);
        return response(true, taskId, javaStatus);
    }

    @Transactional
    public Map<String, Object> submitRun(String taskId, Map<String, Object> run) {
        AiTask task = mapper.find(taskId);
        if (task == null) {
            throw BusinessException.notFound();
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("runId", run.get("runId"));
        request.put("operation", run.get("operation"));
        request.put("status", run.get("status"));
        request.put("inputVersions", json.map(task.getInputVersionsJson()));
        request.put("result", run.get("result"));
        request.put("error", run.get("error"));
        return submit(taskId, request);
    }

    @Transactional
    public void transportFailure(String taskId, String message) {
        AiTask task = mapper.lock(taskId);
        if (task == null || taskService.terminal(task.getStatus()) || "CANCEL_REQUESTED".equals(task.getStatus())) {
            return;
        }
        TaskResultHandler handler = handlers.get(task.getOperation());
        JsonNode error = json.tree(Map.of("code", "MODEL_UNAVAILABLE", "message", message, "retryable", true));
        if (handler != null) {
            handler.failure(task, error);
        }
        finish(task, "FAILED", error, json.digest(Map.of("transport", taskId, "message", message)));
    }

    @Transactional
    public void completeCancellation(String taskId) {
        AiTask task = mapper.lock(taskId);
        if (task == null || !"CANCEL_REQUESTED".equals(task.getStatus())) {
            return;
        }
        TaskResultHandler handler = handlers.get(task.getOperation());
        JsonNode error = json.tree(Map.of("code", "CANCELLED", "message", "任务已取消", "retryable", false));
        if (handler != null) {
            handler.failure(task, error);
        }
        finish(task, "CANCELLED", error, json.digest(Map.of("cancel", taskId)));
    }

    @Transactional
    public void expire(String taskId) {
        AiTask task = mapper.lock(taskId);
        if (task == null || taskService.terminal(task.getStatus())) {
            return;
        }
        if ("CANCEL_REQUESTED".equals(task.getStatus())) {
            completeCancellation(taskId);
            return;
        }
        TaskResultHandler handler = handlers.get(task.getOperation());
        JsonNode error = json.tree(Map.of("code", "DEADLINE_EXCEEDED", "message", "任务已超过截止时间", "retryable", true));
        if (handler != null) {
            handler.failure(task, error);
        }
        finish(task, "EXPIRED", error, json.digest(Map.of("expired", taskId)));
    }

    private void finish(AiTask task, String status, JsonNode error, String digest) {
        String errorJson = json.stringify(error);
        if (mapper.finishFailure(task.getId(), status, digest, errorJson) == 0) {
            throw BusinessException.conflict("任务状态已变化", "INVALID_STATE");
        }
        if ("CANCELLED".equals(status)) {
            taskService.appendEvent(task, "cancelled", Map.of());
        } else {
            taskService.appendEvent(task, "error", json.convert(error, Object.class));
        }
    }

    private Map<String, Object> response(boolean accepted, String taskId, String status) {
        return Map.of("accepted", accepted, "taskId", taskId, "status", status);
    }
}
