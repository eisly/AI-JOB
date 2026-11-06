package com.aijob.backend.task;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.CommonResult;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.common.Responses;

@Service
public class IdempotencyService {

    private final TaskMapper mapper;
    private final JsonService json;

    public IdempotencyService(TaskMapper mapper, JsonService json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Transactional
    public ResponseEntity<CommonResult<Object>> execute(String userId, String method, String route, String key,
                                                        Object request, int status, Supplier<Object> action) {
        return run(userId, method, route, key, request, status,
                () -> new ActionResult(null, action.get()));
    }

    @Transactional
    public ResponseEntity<CommonResult<Object>> executeTask(String userId, String method, String route, String key,
                                                            Object request, Supplier<ActionResult> action) {
        return run(userId, method, route, key, request, 202, action);
    }

    public ResponseEntity<CommonResult<Object>> replayTask(String userId, String method, String route, String key,
                                                            Object request) {
        Map<String, Object> existing = mapper.findIdempotency(userId, method, route, key);
        return existing == null ? null : replay(existing, json.digest(request));
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<CommonResult<Object>> run(String userId, String method, String route, String key,
                                                     Object request, int status, Supplier<ActionResult> action) {
        String digest = json.digest(request);
        String id = UUID.randomUUID().toString();
        int inserted = mapper.insertIdempotency(id, userId, method, route, key, digest,
                LocalDateTime.now(ZoneOffset.UTC).plusHours(24));
        if (inserted == 0) {
            return replay(mapper.lockIdempotency(userId, method, route, key), digest);
        }
        ActionResult result = action.get();
        CommonResult<Object> body = status == 202 ? Responses.accepted(result.data()) : Responses.ok(result.data());
        mapper.completeIdempotency(id, result.taskId(), status, json.stringify(body));
        return ResponseEntity.status(status).body(body);
    }

    private ResponseEntity<CommonResult<Object>> replay(Map<String, Object> existing, String digest) {
        byte[] savedDigest = existing == null ? null : (byte[]) existing.get("request_digest");
        if (savedDigest == null || !HexFormat.of().formatHex(savedDigest).equals(digest)) {
            throw BusinessException.conflict("同一幂等键对应了不同请求", "IDEMPOTENCY_CONFLICT");
        }
        Object response = existing.get("response_json");
        if (response != null) {
            CommonResult<Object> body = json.convert(json.parse(String.valueOf(response)), CommonResult.class);
            return ResponseEntity.status(((Number) existing.get("http_status")).intValue()).body(body);
        }
        Object taskId = existing.get("task_id");
        if (taskId != null) {
            throw BusinessException.taskInProgress(String.valueOf(taskId));
        }
        throw BusinessException.conflict("相同请求正在处理中", "INVALID_STATE");
    }

    public record ActionResult(String taskId, Object data) {
    }
}