package com.aijob.backend.task;

import java.util.Set;

import tools.jackson.databind.JsonNode;

public interface TaskResultHandler {

    Set<String> operations();

    int success(AiTask task, JsonNode result);

    void failure(AiTask task, JsonNode error);

    default void prepareRetry(AiTask original, String newTaskId) {
    }
}