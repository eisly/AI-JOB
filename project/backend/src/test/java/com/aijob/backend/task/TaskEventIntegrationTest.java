package com.aijob.backend.task;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.aijob.backend.common.JsonService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/career_business_test?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.agent.scheduler-enabled=false"
})
@EnabledIfSystemProperty(named = "integration-tests", matches = "true")
class TaskEventIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskMapper mapper;
    @Autowired TaskService tasks;
    @Autowired JsonService json;
    @Autowired TransactionTemplate transactions;

    @Test
    void concurrentEventSyncAndDuplicateTerminalCallbacksRemainOrderedAndIdempotent() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertEquals("career_business_test", connection.getCatalog());
        }
        String taskId = UUID.randomUUID().toString();
        String userId = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        AgentClient agent = mock(AgentClient.class);
        TaskResultHandler handler = mock(TaskResultHandler.class);
        when(handler.operations()).thenReturn(Set.of("JD_PARSE"));
        when(handler.success(any(), any())).thenReturn(1);
        when(handler.resultSummary(any())).thenReturn(Map.of());
        AgentEventSyncService sync = new AgentEventSyncService(agent, mapper, json, transactions, false);
        TaskResultService results = new TaskResultService(mapper, tasks, json, sync, List.of(handler));
        Map<String, Object> callback = Map.of("runId", runId, "operation", "JD_PARSE", "status", "SUCCEEDED",
                "inputVersions", Map.of(), "result", Map.of("title", "并发回调专用测试"));
        var executor = Executors.newFixedThreadPool(3);
        try {
            jdbc.update("INSERT INTO app_user (id,email,password_hash,nickname,role,status) VALUES (?,?,?,'事件测试','USER','ACTIVE')",
                    userId, userId + "@example.invalid", "{test}");
            jdbc.update("""
                    INSERT INTO ai_task (id,user_id,operation,resource_type,resource_id,status,idempotency_key,
                    input_versions_json,input_snapshot_json,agent_run_id,deadline_at)
                    VALUES (?,?,'JD_PARSE','JOB',?,'RUNNING',?,'{}','{}',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))
                    """, taskId, userId, UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(), runId);
            when(agent.runEvents(eq(runId), eq(taskId), anyLong())).thenReturn(Map.of("events", List.of(
                    Map.of("seq", 1, "type", "stage", "payload", Map.of("stage", "START")),
                    Map.of("seq", 2, "type", "reasoning", "payload", Map.of("text", "test")))));
            sync.syncTask(taskId, runId);
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_task_event WHERE task_id=?", Integer.class, taskId));
            when(agent.runEvents(eq(runId), eq(taskId), anyLong())).thenReturn(Map.of("events", List.of(
                    Map.of("seq", 2, "type", "reasoning", "payload", Map.of("text", "test")),
                    Map.of("seq", 3, "type", "stage", "payload", Map.of("stage", "END")))));
            CountDownLatch gate = new CountDownLatch(1);
            var syncFuture = executor.submit(() -> { gate.await(); sync.syncTask(taskId, runId); return null; });
            var first = executor.submit(() -> { gate.await(); return transactions.execute(status -> results.submit(taskId, callback)); });
            var duplicate = executor.submit(() -> { gate.await(); return transactions.execute(status -> results.submit(taskId, callback)); });
            gate.countDown();
            syncFuture.get(20, TimeUnit.SECONDS);
            assertEquals(true, first.get(20, TimeUnit.SECONDS).get("accepted"));
            assertEquals(true, duplicate.get(20, TimeUnit.SECONDS).get("accepted"));
            sync.syncTask(taskId, runId);
            assertEquals(List.of(1L, 2L, 3L, 4L), jdbc.queryForList(
                    "SELECT seq FROM ai_task_event WHERE task_id=? ORDER BY seq", Long.class, taskId));
            assertEquals(List.of("stage", "reasoning", "stage", "done"), jdbc.queryForList(
                    "SELECT event_type FROM ai_task_event WHERE task_id=? ORDER BY seq", String.class, taskId));
            assertEquals("SUCCEEDED", mapper.find(taskId).getStatus());
            assertEquals(4L, mapper.find(taskId).getLastEventSeq());
            assertEquals(1, json.map(mapper.find(taskId).getResultRefJson()).get("version"));
            verify(handler, times(1)).success(any(), any());
            var changed = new java.util.HashMap<String, Object>(callback);
            changed.put("result", Map.of("title", "迟到的冲突结果"));
            assertThrows(com.aijob.backend.common.BusinessException.class,
                    () -> transactions.execute(status -> results.submit(taskId, changed)));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(20, TimeUnit.SECONDS);
            jdbc.update("DELETE FROM ai_task_event WHERE task_id=?", taskId);
            jdbc.update("DELETE FROM ai_task WHERE id=?", taskId);
            jdbc.update("DELETE FROM app_user WHERE id=?", userId);
        }
    }
}
