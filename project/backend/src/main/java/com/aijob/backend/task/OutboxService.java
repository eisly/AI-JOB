package com.aijob.backend.task;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {

    private final TaskMapper mapper;

    public OutboxService(TaskMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Object> claim(String topic) {
        List<Map<String, Object>> rows = mapper.pendingOutbox(topic);
        for (Map<String, Object> row : rows) {
            String id = String.valueOf(row.get("id"));
            String owner = UUID.randomUUID().toString();
            if (mapper.claimOutbox(id, owner, LocalDateTime.now(ZoneOffset.UTC).plusSeconds(90)) == 1) {
                return mapper.outbox(id);
            }
        }
        return null;
    }

    @Transactional
    public void completeDispatch(String outboxId, String owner, String taskId, String runId) {
        if (mapper.doneOutbox(outboxId, owner) == 0) {
            return;
        }
        AiTask task = mapper.lock(taskId);
        if (task != null && !"CANCEL_REQUESTED".equals(task.getStatus()) && task.getAgentRunId() == null) {
            mapper.bindRun(taskId, runId);
        }
    }

    @Transactional
    public void bindDiscoveredRun(String taskId, String runId) {
        AiTask task = mapper.lock(taskId);
        if (task != null && task.getAgentRunId() == null && !"CANCELLED".equals(task.getStatus())) {
            mapper.bindRun(taskId, runId);
        }
    }

    public void done(String outboxId, String owner) {
        mapper.doneOutbox(outboxId, owner);
    }

    public int fail(String outboxId, String owner, String error) {
        Map<String, Object> row = mapper.outbox(outboxId);
        int attempts = ((Number) row.get("attempts")).intValue() + 1;
        String status = attempts >= 4 ? "DEAD" : "PENDING";
        long delay = attempts >= 4 ? 0 : 1000L * (1L << Math.max(0, attempts - 1))
                + ThreadLocalRandom.current().nextLong(251);
        int updated = mapper.retryOutbox(outboxId, owner, status,
                LocalDateTime.now(ZoneOffset.UTC).plusNanos(delay * 1_000_000),
                error == null ? "unknown" : error.substring(0, Math.min(error.length(), 1000)));
        return updated == 1 ? attempts : -1;
    }

    public void recoverLeases() {
        mapper.recoverExpiredLeases();
    }
}