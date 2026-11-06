package com.aijob.backend.task;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;

@Service
public class TaskSseService {

    private final TaskMapper mapper;
    private final TaskService taskService;
    private final JsonService json;
    private final long heartbeatMillis;
    private final ScheduledExecutorService executor;
    private final Map<String, AtomicInteger> connections = new ConcurrentHashMap<>();

    public TaskSseService(TaskMapper mapper, TaskService taskService, JsonService json,
                          @Value("${app.tasks.sse-heartbeat-seconds}") long heartbeatSeconds) {
        this.mapper = mapper;
        this.taskService = taskService;
        this.json = json;
        this.heartbeatMillis = heartbeatSeconds * 1000;
        this.executor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "task-sse");
            thread.setDaemon(true);
            return thread;
        });
    }

    public SseEmitter subscribe(String userId, String taskId, Long lastEventId) {
        return subscribe(userId, taskId, lastEventId, null);
    }

    public SseEmitter subscribe(String userId, String taskId, Long lastEventId, Instant tokenExpiresAt) {
        AiTask task = mapper.findOwned(taskId, userId);
        if (task == null) {
            throw BusinessException.notFound();
        }
        long cursor = lastEventId == null ? 0 : lastEventId;
        if (cursor < 0 || cursor > task.getLastEventSeq()) {
            throw BusinessException.validation("Last-Event-ID", "事件游标超出有效范围");
        }
        Map<String, Object> bounds = mapper.eventBounds(taskId);
        long total = ((Number) bounds.get("total")).longValue();
        if (cursor > 0 && cursor < task.getLastEventSeq() && total == 0) {
            throw BusinessException.eventCursorExpired();
        }
        if (total > 0) {
            long min = ((Number) bounds.get("min_seq")).longValue();
            if (cursor > 0 && min > cursor + 1) {
                throw BusinessException.eventCursorExpired();
            }
        }
        connections.compute(userId, (key, count) -> {
            if (count == null) count = new AtomicInteger();
            if (count.get() >= 3) {
                throw BusinessException.conflict("SSE 连接数已达上限", "INVALID_STATE");
            }
            count.incrementAndGet();
            return count;
        });
        long timeout = tokenExpiresAt == null ? Duration.ofMinutes(30).toMillis()
                : Math.max(1, Math.min(Duration.ofMinutes(30).toMillis(), tokenExpiresAt.toEpochMilli() - System.currentTimeMillis()));
        SseEmitter emitter = new SseEmitter(timeout);
        AtomicLong position = new AtomicLong(cursor);
        AtomicLong heartbeat = new AtomicLong(System.currentTimeMillis());
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                connections.computeIfPresent(userId, (key, current) ->
                        current.decrementAndGet() == 0 ? null : current);
            }
        };
        emitter.onCompletion(release);
        emitter.onTimeout(release);
        emitter.onError(error -> release.run());
        ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];
        Runnable poll = () -> {
            try {
                List<Map<String, Object>> events = mapper.eventsAfter(taskId, position.get());
                for (Map<String, Object> event : events) {
                    long seq = ((Number) event.get("seq")).longValue();
                    Map<String, Object> data = Map.of("taskId", taskId, "seq", seq,
                            "payload", json.convert(json.parse(String.valueOf(event.get("payload_json"))), Object.class));
                    emitter.send(SseEmitter.event().id(String.valueOf(seq))
                            .name(String.valueOf(event.get("event_type"))).data(data, MediaType.APPLICATION_JSON));
                    position.set(seq);
                    heartbeat.set(System.currentTimeMillis());
                }
                AiTask current = mapper.find(taskId);
                if (current == null || (taskService.terminal(current.getStatus()) && events.isEmpty())) {
                    emitter.complete();
                } else if (System.currentTimeMillis() - heartbeat.get() >= heartbeatMillis) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    heartbeat.set(System.currentTimeMillis());
                }
            } catch (Exception ex) {
                emitter.completeWithError(ex);
            }
        };
        holder[0] = executor.scheduleWithFixedDelay(poll, 0, 500, TimeUnit.MILLISECONDS);
        emitter.onCompletion(() -> holder[0].cancel(true));
        emitter.onTimeout(() -> holder[0].cancel(true));
        emitter.onError(error -> holder[0].cancel(true));
        return emitter;
    }
}
