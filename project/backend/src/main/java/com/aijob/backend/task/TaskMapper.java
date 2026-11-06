package com.aijob.backend.task;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TaskMapper {

    @Insert("""
            INSERT INTO ai_task (id,user_id,operation,resource_type,resource_id,status,idempotency_key,
              input_versions_json,input_snapshot_json,retry_of_task_id,last_event_seq,deadline_at)
            VALUES (#{id},#{userId},#{operation},#{resourceType},#{resourceId},'QUEUED',#{idempotencyKey},
              #{inputVersionsJson},#{inputSnapshotJson},#{retryOfTaskId},1,#{deadlineAt})
            """)
    int insertTask(AiTask task);

    @Select("SELECT * FROM ai_task WHERE id=#{id}")
    AiTask find(@Param("id") String id);

    @Select("""
            SELECT * FROM ai_task WHERE resource_type=#{resourceType} AND resource_id=#{resourceId}
            ORDER BY created_at DESC,id DESC LIMIT 1
            """)
    AiTask latestResourceTask(@Param("resourceType") String resourceType, @Param("resourceId") String resourceId);

    @Select("SELECT * FROM ai_task WHERE id=#{id} AND user_id=#{userId}")
    AiTask findOwned(@Param("id") String id, @Param("userId") String userId);

    @Select("SELECT * FROM ai_task WHERE id=#{id} FOR UPDATE")
    AiTask lock(@Param("id") String id);

    @Select("SELECT * FROM ai_task WHERE id=#{id} AND user_id=#{userId} FOR UPDATE")
    AiTask lockOwned(@Param("id") String id, @Param("userId") String userId);

    @Select("SELECT id FROM app_user WHERE id=#{userId} FOR UPDATE")
    String lockUser(@Param("userId") String userId);

    @Select("SELECT COUNT(*) FROM ai_task WHERE user_id=#{userId} AND status IN ('QUEUED','RUNNING','CANCEL_REQUESTED')")
    int activeTaskCount(@Param("userId") String userId);

    @Update("UPDATE ai_task SET status=#{status},version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND status=#{expected}")
    int transition(@Param("id") String id, @Param("expected") String expected, @Param("status") String status);

    @Update("""
            UPDATE ai_task SET agent_run_id=#{runId},status='RUNNING',stage='AI_RUNNING',version=version+1,
              updated_at=UTC_TIMESTAMP(3) WHERE id=#{taskId} AND agent_run_id IS NULL AND status IN ('QUEUED','RUNNING')
            """)
    int bindRun(@Param("taskId") String taskId, @Param("runId") String runId);

    @Update("""
            UPDATE ai_task SET result_ref_json=#{resultRefJson},result_digest=UNHEX(#{digest}),status='SUCCEEDED',
              stage='DONE',progress=100,finished_at=UTC_TIMESTAMP(3),error_json=NULL,version=version+1,
              updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND status IN ('QUEUED','RUNNING')
            """)
    int succeed(@Param("id") String id, @Param("resultRefJson") String resultRefJson, @Param("digest") String digest);

    @Update("""
            UPDATE ai_task SET status=#{status},result_digest=UNHEX(#{digest}),error_json=#{errorJson},stage='FINISHED',
              finished_at=UTC_TIMESTAMP(3),version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND status IN ('QUEUED','RUNNING','CANCEL_REQUESTED')
            """)
    int finishFailure(@Param("id") String id, @Param("status") String status,
                      @Param("digest") String digest, @Param("errorJson") String errorJson);

    @Update("UPDATE ai_task SET last_event_seq=#{seq},version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{taskId}")
    int updateLastEvent(@Param("taskId") String taskId, @Param("seq") long seq);

    @Insert("""
            INSERT INTO ai_task_event (id,task_id,seq,event_type,payload_json,expires_at)
            VALUES (#{id},#{taskId},#{seq},#{eventType},#{payloadJson},#{expiresAt})
            """)
    int insertEvent(@Param("id") String id, @Param("taskId") String taskId, @Param("seq") long seq,
                    @Param("eventType") String eventType, @Param("payloadJson") String payloadJson,
                    @Param("expiresAt") LocalDateTime expiresAt);

    @Select("""
            SELECT id,task_id,seq,event_type,payload_json,created_at,expires_at FROM ai_task_event
            WHERE task_id=#{taskId} AND seq>#{cursor} AND expires_at>UTC_TIMESTAMP(3) ORDER BY seq LIMIT 200
            """)
    List<Map<String,Object>> eventsAfter(@Param("taskId") String taskId, @Param("cursor") long cursor);

    @Select("""
            SELECT MIN(seq) AS min_seq,MAX(seq) AS max_seq,COUNT(*) AS total FROM ai_task_event
            WHERE task_id=#{taskId} AND expires_at>UTC_TIMESTAMP(3)
            """)
    Map<String,Object> eventBounds(@Param("taskId") String taskId);

    @Select("""
            <script>
            SELECT * FROM ai_task WHERE user_id=#{userId}
            <if test="statuses != null and !statuses.isEmpty()">AND status IN
              <foreach collection="statuses" item="status" open="(" separator="," close=")">#{status}</foreach>
            </if>
            ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<AiTask> page(@Param("userId") String userId, @Param("statuses") List<String> statuses,
                      @Param("size") int size, @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM ai_task WHERE user_id=#{userId}
            <if test="statuses != null and !statuses.isEmpty()">AND status IN
              <foreach collection="statuses" item="status" open="(" separator="," close=")">#{status}</foreach>
            </if>
            </script>
            """)
    long count(@Param("userId") String userId, @Param("statuses") List<String> statuses);

    @Select("SELECT * FROM ai_task WHERE status='RUNNING' ORDER BY updated_at LIMIT 100")
    List<AiTask> runningTasks();

    @Select("SELECT * FROM ai_task WHERE status IN ('QUEUED','RUNNING','CANCEL_REQUESTED') AND deadline_at<UTC_TIMESTAMP(3) LIMIT 100")
    List<AiTask> expiredTasks();

    @Select("""
            SELECT * FROM api_idempotency WHERE user_id=#{userId} AND http_method=#{method}
              AND route_key=#{route} AND request_key=#{key} FOR UPDATE
            """)
    Map<String,Object> lockIdempotency(@Param("userId") String userId, @Param("method") String method,
                                       @Param("route") String route, @Param("key") String key);

    @Select("""
            SELECT * FROM api_idempotency WHERE user_id=#{userId} AND http_method=#{method}
              AND route_key=#{route} AND request_key=#{key}
            """)
    Map<String,Object> findIdempotency(@Param("userId") String userId, @Param("method") String method,
                                       @Param("route") String route, @Param("key") String key);

    @Insert("""
            INSERT IGNORE INTO api_idempotency (id,user_id,http_method,route_key,request_key,request_digest,status,expires_at)
            VALUES (#{id},#{userId},#{method},#{route},#{key},UNHEX(#{digest}),'PROCESSING',#{expiresAt})
            """)
    int insertIdempotency(@Param("id") String id, @Param("userId") String userId,
                          @Param("method") String method, @Param("route") String route,
                          @Param("key") String key, @Param("digest") String digest,
                          @Param("expiresAt") LocalDateTime expiresAt);

    @Update("""
            UPDATE api_idempotency SET task_id=#{taskId},http_status=#{httpStatus},response_json=#{responseJson},
              status='COMPLETED',updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}
            """)
    int completeIdempotency(@Param("id") String id, @Param("taskId") String taskId,
                            @Param("httpStatus") int httpStatus, @Param("responseJson") String responseJson);

    @Insert("""
            INSERT INTO outbox_event (id,topic,aggregate_id,dedupe_key,payload_json,next_attempt_at)
            VALUES (#{id},#{topic},#{aggregateId},#{dedupeKey},#{payloadJson},UTC_TIMESTAMP(3))
            """)
    int insertOutbox(@Param("id") String id, @Param("topic") String topic,
                     @Param("aggregateId") String aggregateId, @Param("dedupeKey") String dedupeKey,
                     @Param("payloadJson") String payloadJson);

    @Select("""
            SELECT * FROM outbox_event WHERE topic=#{topic} AND status='PENDING' AND next_attempt_at<=UTC_TIMESTAMP(3)
            ORDER BY created_at,id LIMIT 20
            """)
    List<Map<String,Object>> pendingOutbox(@Param("topic") String topic);

    @Select("SELECT * FROM outbox_event WHERE id=#{id}")
    Map<String,Object> outbox(@Param("id") String id);

    @Update("""
            UPDATE outbox_event SET status='PROCESSING',lease_owner=#{owner},lease_until=#{leaseUntil},updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND status='PENDING' AND next_attempt_at<=UTC_TIMESTAMP(3)
            """)
    int claimOutbox(@Param("id") String id, @Param("owner") String owner, @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("UPDATE outbox_event SET status='DONE',last_error=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND status='PROCESSING' AND lease_owner=#{owner}")
    int doneOutbox(@Param("id") String id, @Param("owner") String owner);

    @Update("""
            UPDATE outbox_event SET status=#{status},attempts=attempts+1,next_attempt_at=#{nextAttemptAt},
              lease_owner=NULL,lease_until=NULL,last_error=#{error},updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND status='PROCESSING' AND lease_owner=#{owner}
            """)
    int retryOutbox(@Param("id") String id, @Param("owner") String owner, @Param("status") String status,
                    @Param("nextAttemptAt") LocalDateTime nextAttemptAt, @Param("error") String error);

    @Update("""
            UPDATE outbox_event SET status=IF(attempts>=3,'DEAD','PENDING'),attempts=attempts+1,
              lease_owner=NULL,lease_until=NULL,last_error='lease expired',updated_at=UTC_TIMESTAMP(3)
            WHERE status='PROCESSING' AND lease_until<UTC_TIMESTAMP(3)
            """)
    int recoverExpiredLeases();
}