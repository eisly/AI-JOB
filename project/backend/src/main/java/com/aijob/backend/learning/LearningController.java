package com.aijob.backend.learning;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
@RequestMapping("/api/v1/learning")
public class LearningController {

    private final CurrentUserResolver users;
    private final LearningService learning;
    private final PracticeService practice;

    public LearningController(CurrentUserResolver users, LearningService learning, PracticeService practice) {
        this.users = users;
        this.learning = learning;
        this.practice = practice;
    }

    // ===== 整组训练（一次预生成一组题目）=====

    @PostMapping("/practice-sets")
    public ResponseEntity<CommonResult<Object>> createPracticeSet(@RequestHeader("Idempotency-Key") String key,
                                                                  @RequestBody Map<String, Object> body,
                                                                  HttpServletRequest request) {
        return practice.create(users.resolveCurrentUserId(), request.getRequestURI(),
                Data.idempotencyKey(key), body);
    }

    @GetMapping("/practice-sets/{setId}")
    public CommonResult<Map<String, Object>> practiceSet(@PathVariable String setId) {
        return Responses.ok(practice.detail(users.resolveCurrentUserId(), Data.uuid(setId, "setId")));
    }

    @PostMapping("/practice-sets/{setId}/submission")
    public ResponseEntity<CommonResult<Object>> submitPracticeSet(@PathVariable String setId,
                                                                  @RequestHeader("Idempotency-Key") String key,
                                                                  @RequestBody Map<String, Object> body,
                                                                  HttpServletRequest request) {
        return practice.submit(users.resolveCurrentUserId(), Data.uuid(setId, "setId"),
                request.getRequestURI(), Data.idempotencyKey(key), body);
    }

    @GetMapping("/practice-sets/{setId}/result")
    public CommonResult<Map<String, Object>> practiceSetResult(@PathVariable String setId) {
        return Responses.ok(practice.result(users.resolveCurrentUserId(), Data.uuid(setId, "setId")));
    }

    @PostMapping("/plans")
    public ResponseEntity<CommonResult<Object>> createPlan(@RequestHeader("Idempotency-Key") String key,
                                                            @RequestBody Map<String, Object> body,
                                                            HttpServletRequest request) {
        return learning.createPlan(users.resolveCurrentUserId(), request.getRequestURI(), Data.idempotencyKey(key), body);
    }

    @PostMapping("/plans/from-resume")
    public ResponseEntity<CommonResult<Object>> createPlanFromResume(@RequestHeader("Idempotency-Key") String key,
                                                                      @RequestBody Map<String, Object> body,
                                                                      HttpServletRequest request) {
        return learning.createPlanFromResume(users.resolveCurrentUserId(), request.getRequestURI(),
                Data.idempotencyKey(key), body);
    }

    @GetMapping("/plans")
    public CommonResult<PageResult<Map<String, Object>>> plans(@RequestParam(required = false) Integer current,
                                                                @RequestParam(required = false) Integer size) {
        return Responses.ok(learning.plans(users.resolveCurrentUserId(), current, size));
    }

    @GetMapping("/plans/{planId}")
    public CommonResult<Map<String, Object>> plan(@PathVariable String planId) {
        return Responses.ok(learning.plan(users.resolveCurrentUserId(), Data.uuid(planId, "planId")));
    }

    @PostMapping("/sessions")
    public ResponseEntity<CommonResult<Object>> createSession(@RequestHeader("Idempotency-Key") String key,
                                                               @RequestBody Map<String, Object> body,
                                                               HttpServletRequest request) {
        return learning.createSession(users.resolveCurrentUserId(), request.getRequestURI(), Data.idempotencyKey(key), body);
    }

    @GetMapping("/sessions/{sessionId}")
    public CommonResult<Map<String, Object>> session(@PathVariable String sessionId) {
        return Responses.ok(learning.session(users.resolveCurrentUserId(), Data.uuid(sessionId, "sessionId")));
    }

    @PostMapping("/sessions/{sessionId}/answers")
    public ResponseEntity<CommonResult<Object>> answer(@PathVariable String sessionId,
                                                        @RequestHeader("Idempotency-Key") String key,
                                                        @RequestBody Map<String, Object> body,
                                                        HttpServletRequest request) {
        String id = Data.uuid(sessionId, "sessionId");
        return learning.answer(users.resolveCurrentUserId(), id, request.getRequestURI(), Data.idempotencyKey(key), body);
    }

    @GetMapping("/answers/{answerId}")
    public CommonResult<Map<String, Object>> answer(@PathVariable String answerId) {
        return Responses.ok(learning.answerDetail(users.resolveCurrentUserId(), Data.uuid(answerId, "answerId")));
    }

    @GetMapping("/mistakes")
    public CommonResult<PageResult<Map<String, Object>>> mistakes(@RequestParam(required = false) Integer current,
                                                                   @RequestParam(required = false) Integer size,
                                                                   @RequestParam(required = false) Boolean resolved) {
        return Responses.ok(learning.mistakes(users.resolveCurrentUserId(), current, size, resolved));
    }

    @GetMapping("/mistakes/{mistakeId}")
    public CommonResult<Map<String, Object>> mistake(@PathVariable String mistakeId) {
        return Responses.ok(learning.mistake(users.resolveCurrentUserId(), Data.uuid(mistakeId, "mistakeId")));
    }

    @PostMapping("/mistakes/{mistakeId}/retry")
    public ResponseEntity<CommonResult<Object>> retry(@PathVariable String mistakeId,
                                                       @RequestHeader("Idempotency-Key") String key,
                                                       @RequestBody(required = false) Map<String, Object> body,
                                                       HttpServletRequest request) {
        String id = Data.uuid(mistakeId, "mistakeId");
        return learning.retryMistake(users.resolveCurrentUserId(), id, request.getRequestURI(), Data.idempotencyKey(key),
                body == null ? Map.of() : body);
    }

    /** 错题重练：复用原题创建单题练习集，返回可直接打开独立答题页的练习集。 */
    @PostMapping("/mistakes/{mistakeId}/retry-practice")
    public CommonResult<Map<String, Object>> retryPractice(@PathVariable String mistakeId) {
        return Responses.ok(practice.retryMistakeAsSet(users.resolveCurrentUserId(),
                Data.uuid(mistakeId, "mistakeId")));
    }

    @PutMapping("/plans/{planId}/items/{itemId}/completion")
    public CommonResult<Boolean> complete(@PathVariable String planId, @PathVariable String itemId,
                                           @RequestBody(required = false) Map<String, Object> body) {
        return Responses.ok(learning.completeReading(users.resolveCurrentUserId(), Data.uuid(planId, "planId"),
                Data.uuid(itemId, "itemId")));
    }
}