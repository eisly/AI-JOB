package com.aijob.backend.task;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aijob.backend.common.CommonResult;
import com.aijob.backend.common.CurrentUserResolver;
import com.aijob.backend.common.Data;
import com.aijob.backend.common.PageResult;
import com.aijob.backend.common.Responses;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final CurrentUserResolver users;
    private final TaskService tasks;
    private final TaskRetryService retries;
    private final TaskSseService streams;

    public TaskController(CurrentUserResolver users, TaskService tasks, TaskRetryService retries, TaskSseService streams) {
        this.users = users;
        this.tasks = tasks;
        this.retries = retries;
        this.streams = streams;
    }

    @GetMapping
    public CommonResult<PageResult<Map<String, Object>>> list(@RequestParam(required = false) String status,
                                                               @RequestParam(required = false) Integer current,
                                                               @RequestParam(required = false) Integer size) {
        return Responses.ok(tasks.list(users.resolveCurrentUserId(), status, current, size));
    }

    @GetMapping("/{taskId}")
    public CommonResult<Map<String, Object>> detail(@PathVariable String taskId) {
        return Responses.ok(tasks.detail(users.resolveCurrentUserId(), Data.uuid(taskId, "taskId")));
    }

    @PostMapping("/{taskId}/cancel")
    public CommonResult<Map<String, Object>> cancel(@PathVariable String taskId,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        return Responses.ok(tasks.cancel(users.resolveCurrentUserId(), Data.uuid(taskId, "taskId")));
    }

    @PostMapping("/{taskId}/retry")
    public ResponseEntity<CommonResult<Object>> retry(@PathVariable String taskId,
                                                       @RequestHeader("Idempotency-Key") String key,
                                                       @RequestBody(required = false) Map<String, Object> body,
                                                       HttpServletRequest request) {
        String id = Data.uuid(taskId, "taskId");
        return retries.retry(users.resolveCurrentUserId(), id, Data.idempotencyKey(key), request.getRequestURI());
    }

    @GetMapping("/{taskId}/events")
    public ResponseEntity<SseEmitter> events(@PathVariable String taskId,
                                              @RequestHeader(value = "Last-Event-ID", required = false) Long cursor,
                                              @AuthenticationPrincipal Jwt jwt) {
        String userId = users.resolveCurrentUserId();
        SseEmitter emitter = streams.subscribe(userId, Data.uuid(taskId, "taskId"), cursor,
                jwt == null ? null : jwt.getExpiresAt());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/event-stream;charset=utf-8")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }
}