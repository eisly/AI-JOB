package com.aijob.backend.task;

import java.time.LocalDateTime;

public class AiTask {

    private String id;
    private String userId;
    private String operation;
    private String resourceType;
    private String resourceId;
    private String status;
    private String idempotencyKey;
    private String inputVersionsJson;
    private String inputSnapshotJson;
    private String agentRunId;
    private String retryOfTaskId;
    private String resultRefJson;
    private byte[] resultDigest;
    private String errorJson;
    private String stage;
    private Integer progress;
    private Long lastEventSeq;
    private LocalDateTime deadlineAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private Integer version;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getInputVersionsJson() { return inputVersionsJson; }
    public void setInputVersionsJson(String inputVersionsJson) { this.inputVersionsJson = inputVersionsJson; }
    public String getInputSnapshotJson() { return inputSnapshotJson; }
    public void setInputSnapshotJson(String inputSnapshotJson) { this.inputSnapshotJson = inputSnapshotJson; }
    public String getAgentRunId() { return agentRunId; }
    public void setAgentRunId(String agentRunId) { this.agentRunId = agentRunId; }
    public String getRetryOfTaskId() { return retryOfTaskId; }
    public void setRetryOfTaskId(String retryOfTaskId) { this.retryOfTaskId = retryOfTaskId; }
    public String getResultRefJson() { return resultRefJson; }
    public void setResultRefJson(String resultRefJson) { this.resultRefJson = resultRefJson; }
    public byte[] getResultDigest() { return resultDigest; }
    public void setResultDigest(byte[] resultDigest) { this.resultDigest = resultDigest; }
    public String getErrorJson() { return errorJson; }
    public void setErrorJson(String errorJson) { this.errorJson = errorJson; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
    public Long getLastEventSeq() { return lastEventSeq; }
    public void setLastEventSeq(Long lastEventSeq) { this.lastEventSeq = lastEventSeq; }
    public LocalDateTime getDeadlineAt() { return deadlineAt; }
    public void setDeadlineAt(LocalDateTime deadlineAt) { this.deadlineAt = deadlineAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}