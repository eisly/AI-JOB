package com.aijob.backend.task;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.aijob.backend.common.JsonService;

/**
 * 把 Agent 运行期间产生的事件（阶段与模型思考过程）同步到 ai_task_event，
 * 使前端可通过任务事件 SSE 实时展示模型思考过程。
 */
@Component
public class AgentEventSyncService {

    private static final Logger log = LoggerFactory.getLogger(AgentEventSyncService.class);

    private static final Set<String> FORWARDED = Set.of("stage", "reasoning");

    private final AgentClient agent;
    private final TaskMapper tasks;
    private final JsonService json;
    private final TransactionTemplate transactions;
    private final boolean enabled;

    public AgentEventSyncService(AgentClient agent, TaskMapper tasks, JsonService json,
                                 TransactionTemplate transactions,
                                 @Value("${app.agent.scheduler-enabled}") boolean enabled) {
        this.agent = agent;
        this.tasks = tasks;
        this.json = json;
        this.transactions = transactions;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "2000", initialDelayString = "3000")
    public void sync() {
        if (!enabled) {
            return;
        }
        for (AiTask task : tasks.runningTasks()) {
            String runId = task.getAgentRunId();
            if (runId == null || runId.isBlank()) {
                continue;
            }
            syncTask(task.getId(), runId);
        }
    }

    /**
     * 同步单个任务的 Agent 事件。运行中由定时器调用；任务回调终态前也会调用，
     * 避免短任务在轮询间隙结束导致思考过程事件丢失。
     *
     * 序号分配必须与回调线程串行化：定时器线程与回调线程可能并发同步同一任务，
     * 若各自以 MAX(seq)+1 分配会取到相同序号并触发 uk_task_id_seq 唯一键冲突。
     * 因此"锁定任务行 + 存在性检查 + 序号分配 + 插入"放入同一事务：
     * 回调线程加入其外层事务（已持有行锁），定时器线程新建事务并等待行锁，
     * 锁释放后 agentEventExists 能看到已提交事件，天然幂等。
     */
    public void syncTask(String taskId, String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        try {
            long after = tasks.agentEventCursor(taskId);
            Map<String, Object> page = agent.runEvents(runId, taskId, after);
            if (page == null || !(page.get("events") instanceof List<?> events)) {
                return;
            }
            List<Object[]> pending = new ArrayList<>();
            for (Object item : events) {
                if (!(item instanceof Map<?, ?> event)) {
                    continue;
                }
                String type = String.valueOf(event.get("type"));
                if (!FORWARDED.contains(type) || !(event.get("seq") instanceof Number seqNumber)) {
                    continue;
                }
                pending.add(new Object[] { type, seqNumber.longValue(),
                        event.get("payload") == null ? Map.of() : event.get("payload") });
            }
            if (pending.isEmpty()) {
                return;
            }
            transactions.executeWithoutResult(status -> {
                tasks.lock(taskId);
                for (Object[] entry : pending) {
                    String type = (String) entry[0];
                    long agentSeq = (Long) entry[1];
                    if (tasks.agentEventExists(taskId, runId, agentSeq) > 0) {
                        continue;
                    }
                    long seq = tasks.nextEventSeq(taskId);
                    tasks.insertAgentEvent(UUID.randomUUID().toString(), taskId, seq, type,
                            json.stringify(entry[2]), runId, agentSeq,
                            LocalDateTime.now(ZoneOffset.UTC).plusHours(24));
                    tasks.updateLastEvent(taskId, seq);
                }
            });
        } catch (Exception ex) {
            log.debug("agent event sync skipped task={} reason={}", taskId, ex.getClass().getSimpleName());
        }
    }
}