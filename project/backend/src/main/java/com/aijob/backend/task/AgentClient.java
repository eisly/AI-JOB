package com.aijob.backend.task;

import java.util.Map;

public interface AgentClient {

    Map<String, Object> submit(AiTask task);

    Map<String, Object> findByTask(String taskId);

    Map<String, Object> cancel(String runId, String taskId);
}