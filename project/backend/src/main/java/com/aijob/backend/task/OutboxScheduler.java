package com.aijob.backend.task;

import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.Data;

@Component
public class OutboxScheduler {

    private final OutboxService outbox;
    private final AgentClient agent;
    private final TaskMapper tasks;
    private final TaskResultService results;
    private final boolean enabled;

    public OutboxScheduler(OutboxService outbox, AgentClient agent, TaskMapper tasks, TaskResultService results,
                           @Value("${app.agent.scheduler-enabled}") boolean enabled) {
        this.outbox = outbox;
        this.agent = agent;
        this.tasks = tasks;
        this.results = results;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "1000", initialDelayString = "2000")
    public void dispatch() {
        if (!enabled) {
            return;
        }
        outbox.recoverLeases();
        Map<String, Object> event;
        while ((event = outbox.claim("AI_DISPATCH")) != null) {
            String outboxId = String.valueOf(event.get("id"));
            String owner = String.valueOf(event.get("lease_owner"));
            String taskId = String.valueOf(event.get("aggregate_id"));
            AiTask task = tasks.find(taskId);
            if (task == null || Set.of("CANCEL_REQUESTED", "CANCELLED", "SUCCEEDED", "FAILED", "EXPIRED").contains(task.getStatus())) {
                outbox.done(outboxId, owner);
                continue;
            }
            try {
                Map<String, Object> run = agent.submit(task);
                outbox.completeDispatch(outboxId, owner, taskId, validRun(task, run));
            } catch (Exception ex) {
                recoverDispatch(outboxId, owner, taskId, ex);
            }
        }
    }

    @Scheduled(fixedDelayString = "1000", initialDelayString = "2500")
    public void cancel() {
        if (!enabled) {
            return;
        }
        Map<String, Object> event;
        while ((event = outbox.claim("AI_CANCEL")) != null) {
            String outboxId = String.valueOf(event.get("id"));
            String owner = String.valueOf(event.get("lease_owner"));
            String taskId = String.valueOf(event.get("aggregate_id"));
            AiTask task = tasks.find(taskId);
            try {
                if (task == null) {
                    outbox.done(outboxId, owner);
                    continue;
                }
                String runId = task.getAgentRunId();
                if (runId == null) {
                    Map<String, Object> run = agent.findByTask(taskId);
                    runId = validRun(task, run);
                    outbox.bindDiscoveredRun(taskId, runId);
                }
                agent.cancel(runId, taskId);
                outbox.done(outboxId, owner);
            } catch (Exception ex) {
                outbox.fail(outboxId, owner, ex.getClass().getSimpleName());
            } finally {
                // Java cancellation must not wait for an unavailable or not-yet-created Agent run.
                if (task != null) results.completeCancellation(taskId);
            }
        }
    }

    @Scheduled(fixedDelayString = "30000", initialDelayString = "10000")
    public void reconcile() {
        if (!enabled) {
            return;
        }
        for (AiTask task : tasks.runningTasks()) {
            try {
                Map<String, Object> run = agent.findByTask(task.getId());
                validRun(task, run);
                String status = String.valueOf(run.get("status"));
                if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status)) {
                    results.submitRun(task.getId(), run);
                }
            } catch (Exception ignored) {
            }
        }
        for (AiTask task : tasks.expiredTasks()) {
            try {
                results.expire(task.getId());
            } catch (Exception ignored) {
            }
        }
    }

    private void recoverDispatch(String outboxId, String owner, String taskId, Exception original) {
        AiTask task = tasks.find(taskId);
        try {
            if (task != null) {
                Map<String, Object> run = agent.findByTask(taskId);
                outbox.completeDispatch(outboxId, owner, taskId, validRun(task, run));
                return;
            }
        } catch (Exception ignored) {
        }
        int attempts = outbox.fail(outboxId, owner, original.getClass().getSimpleName());
        if (attempts >= 4) {
            results.transportFailure(taskId, "Agent 暂不可用");
        }
    }

    private String validRun(AiTask task, Map<String, Object> run) {
        if (run == null || !task.getId().equals(String.valueOf(run.get("taskId")))
                || !task.getOperation().equals(String.valueOf(run.get("operation")))) {
            throw BusinessException.dependencyUnavailable("Agent 返回了不匹配的运行");
        }
        String runId = Data.uuid(String.valueOf(run.get("runId")), "runId");
        if (!Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCEL_REQUESTED", "CANCELLED")
                .contains(String.valueOf(run.get("status")))) {
            throw BusinessException.dependencyUnavailable("Agent 返回了非法运行状态");
        }
        return runId;
    }
}
