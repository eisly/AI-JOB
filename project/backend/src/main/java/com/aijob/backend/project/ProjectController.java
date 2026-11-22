package com.aijob.backend.project;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aijob.backend.common.CommonResult;
import com.aijob.backend.common.CurrentUserResolver;
import com.aijob.backend.common.Data;
import com.aijob.backend.common.PageResult;
import com.aijob.backend.common.Responses;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final CurrentUserResolver users;
    private final ProjectService projects;

    public ProjectController(CurrentUserResolver users, ProjectService projects) {
        this.users = users;
        this.projects = projects;
    }

    @PostMapping("/recommendations")
    public ResponseEntity<CommonResult<Object>> recommend(@RequestHeader("Idempotency-Key") String key,
                                                           @RequestBody Map<String, Object> body,
                                                           HttpServletRequest request) {
        return projects.recommend(users.resolveCurrentUserId(), request.getRequestURI(), Data.idempotencyKey(key), body);
    }

    @GetMapping("/recommendations/{recommendationId}")
    public CommonResult<Map<String, Object>> recommendation(@PathVariable String recommendationId) {
        return Responses.ok(projects.recommendation(users.resolveCurrentUserId(),
                Data.uuid(recommendationId, "recommendationId")));
    }

    @PostMapping("/recommendations/{recommendationId}/more")
    public ResponseEntity<CommonResult<Object>> more(@PathVariable String recommendationId,
                                                      @RequestHeader("Idempotency-Key") String key,
                                                      @RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        return projects.more(users.resolveCurrentUserId(), Data.uuid(recommendationId, "recommendationId"),
                request.getRequestURI(), Data.idempotencyKey(key), body);
    }

    @PostMapping("/plans")
    public ResponseEntity<CommonResult<Object>> createPlan(@RequestHeader("Idempotency-Key") String key,
                                                            @RequestBody Map<String, Object> body,
                                                            HttpServletRequest request) {
        return projects.createPlan(users.resolveCurrentUserId(), request.getRequestURI(), Data.idempotencyKey(key), body);
    }

    @GetMapping
    public CommonResult<PageResult<Map<String, Object>>> projects(@RequestParam(required = false) Integer current,
                                                                   @RequestParam(required = false) Integer size) {
        return Responses.ok(projects.projects(users.resolveCurrentUserId(), current, size));
    }

    @GetMapping("/{projectId}")
    public CommonResult<Map<String, Object>> project(@PathVariable String projectId) {
        return Responses.ok(projects.project(users.resolveCurrentUserId(), Data.uuid(projectId, "projectId")));
    }

    @PatchMapping("/{projectId}/tasks/{taskId}")
    public CommonResult<Map<String, Object>> updateTask(@PathVariable String projectId,
                                                         @PathVariable String taskId,
                                                         @RequestBody Map<String, Object> body) {
        return Responses.ok(projects.updateTask(users.resolveCurrentUserId(), Data.uuid(projectId, "projectId"),
                Data.uuid(taskId, "taskId"), body));
    }

    @PostMapping("/{projectId}/deliverables")
    public ResponseEntity<CommonResult<Object>> addDeliverable(@PathVariable String projectId,
                                                                @RequestHeader("Idempotency-Key") String key,
                                                                @RequestBody Map<String, Object> body,
                                                                HttpServletRequest request) {
        return projects.addDeliverable(users.resolveCurrentUserId(), Data.uuid(projectId, "projectId"),
                request.getRequestURI(), Data.idempotencyKey(key), body);
    }
}
