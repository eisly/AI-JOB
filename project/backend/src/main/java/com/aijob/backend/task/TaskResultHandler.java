package com.aijob.backend.task;

import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

public interface TaskResultHandler {

    Set<String> operations();

    int success(AiTask task, JsonNode result);

    void failure(AiTask task, JsonNode error);

    default void prepareRetry(AiTask original, String newTaskId) {
    }

    /**
     * 业务结果摘要（S03/A05）：success 落库后由 TaskResultService 调用，
     * 返回的键值会合并进任务 result reference，供任务列表区分
     * "执行完成"与"业务结果不可用/部分失败"（如搜索 outcome）。
     * 默认无摘要；需要区分业务结果的处理器覆写此方法。
     */
    default Map<String, Object> resultSummary(AiTask task) {
        return Map.of();
    }
}