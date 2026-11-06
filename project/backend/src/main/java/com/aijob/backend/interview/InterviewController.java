package com.aijob.backend.interview;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
@RequestMapping("/api/v1/interviews")
public class InterviewController {

    private final CurrentUserResolver users;
    private final InterviewService interviews;

    public InterviewController(CurrentUserResolver users, InterviewService interviews) {
        this.users = users;
        this.interviews = interviews;
    }

    @PostMapping
    public ResponseEntity<CommonResult<Object>> create(@RequestHeader("Idempotency-Key") String key,
                                                        @RequestBody Map<String, Object> body,
                                                        HttpServletRequest request) {
        return interviews.create(users.resolveCurrentUserId(), request.getRequestURI(), Data.idempotencyKey(key), body);
    }

    @GetMapping
    public CommonResult<PageResult<Map<String, Object>>> list(@RequestParam(required = false) Integer current,
                                                               @RequestParam(required = false) Integer size) {
        return Responses.ok(interviews.list(users.resolveCurrentUserId(), current, size));
    }

    @GetMapping("/{interviewId}")
    public CommonResult<Map<String, Object>> detail(@PathVariable String interviewId) {
        return Responses.ok(interviews.detail(users.resolveCurrentUserId(), Data.uuid(interviewId, "interviewId")));
    }

    @PostMapping("/{interviewId}/answers")
    public ResponseEntity<CommonResult<Object>> answer(@PathVariable String interviewId,
                                                        @RequestHeader("Idempotency-Key") String key,
                                                        @RequestBody Map<String, Object> body,
                                                        HttpServletRequest request) {
        String id = Data.uuid(interviewId, "interviewId");
        return interviews.answer(users.resolveCurrentUserId(), id, request.getRequestURI(), Data.idempotencyKey(key), body);
    }

    @PostMapping("/{interviewId}/end")
    public ResponseEntity<CommonResult<Object>> end(@PathVariable String interviewId,
                                                     @RequestHeader("Idempotency-Key") String key,
                                                     @RequestBody Map<String, Object> body,
                                                     HttpServletRequest request) {
        String id = Data.uuid(interviewId, "interviewId");
        return interviews.end(users.resolveCurrentUserId(), id, request.getRequestURI(), Data.idempotencyKey(key), body);
    }

    @GetMapping("/{interviewId}/report")
    public CommonResult<Map<String, Object>> report(@PathVariable String interviewId) {
        return Responses.ok(interviews.report(users.resolveCurrentUserId(), Data.uuid(interviewId, "interviewId")));
    }
}