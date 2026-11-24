package com.aijob.backend.job;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1")
public class JobController {

    private final CurrentUserResolver users;
    private final JobService jobs;

    public JobController(CurrentUserResolver users, JobService jobs) {
        this.users = users;
        this.jobs = jobs;
    }

    @GetMapping("/jobs")
    public CommonResult<PageResult<Map<String, Object>>> jobs(@RequestParam(required = false) Integer current,
                                                               @RequestParam(required = false) Integer size,
                                                               @RequestParam(required = false) String q,
                                                               @RequestParam(required = false) String cityCode,
                                                               @RequestParam(required = false) String candidateType) {
        return Responses.ok(jobs.jobs(users.resolveCurrentUserId(), current, size, q, cityCode, candidateType));
    }

    @GetMapping("/jobs/imports")
    public CommonResult<PageResult<Map<String, Object>>> imports(@RequestParam(required = false) Integer current,
                                                                  @RequestParam(required = false) Integer size) {
        return Responses.ok(jobs.imports(users.resolveCurrentUserId(), current, size));
    }

    @GetMapping("/jobs/{jobId}")
    public CommonResult<Map<String, Object>> job(@PathVariable String jobId) {
        return Responses.ok(jobs.job(users.resolveCurrentUserId(), Data.uuid(jobId, "jobId")));
    }

    @PostMapping("/jobs/imports")
    public ResponseEntity<CommonResult<Object>> importJob(@RequestHeader("Idempotency-Key") String key,
                                                           @RequestBody Map<String, Object> body,
                                                           HttpServletRequest request) {
        return jobs.importJob(users.resolveCurrentUserId(), request.getRequestURI(), Data.idempotencyKey(key), body);
    }

    @PostMapping("/jobs/{jobId}/save-to-personal")
    public CommonResult<Map<String, Object>> saveToPersonal(@PathVariable String jobId) {
        return Responses.ok(jobs.saveToPersonal(users.resolveCurrentUserId(), Data.uuid(jobId, "jobId")));
    }

    @PutMapping("/jobs/{jobId}/extracted-fields")
    public CommonResult<Map<String, Object>> correctFields(@PathVariable String jobId,
                                                            @RequestBody Map<String, Object> body) {
        return Responses.ok(jobs.correctPrivateJob(users.resolveCurrentUserId(), Data.uuid(jobId, "jobId"), body));
    }

    @PutMapping("/favorites/{jobId}")
    public CommonResult<Boolean> favorite(@PathVariable String jobId,
                                           @RequestBody(required = false) Map<String, Object> body) {
        return Responses.ok(jobs.favorite(users.resolveCurrentUserId(), Data.uuid(jobId, "jobId")));
    }

    @DeleteMapping("/favorites/{jobId}")
    public CommonResult<Boolean> unfavorite(@PathVariable String jobId) {
        return Responses.ok(jobs.unfavorite(users.resolveCurrentUserId(), Data.uuid(jobId, "jobId")));
    }

    @GetMapping("/favorites")
    public CommonResult<PageResult<Map<String, Object>>> favorites(@RequestParam(required = false) Integer current,
                                                                    @RequestParam(required = false) Integer size) {
        return Responses.ok(jobs.favorites(users.resolveCurrentUserId(), current, size));
    }

    @PostMapping("/job-matches")
    public ResponseEntity<CommonResult<Object>> match(@RequestHeader("Idempotency-Key") String key,
                                                       @RequestBody Map<String, Object> body,
                                                       HttpServletRequest request) {
        return jobs.match(users.resolveCurrentUserId(), request.getRequestURI(), Data.idempotencyKey(key), body);
    }

    @GetMapping("/job-matches/{matchId}")
    public CommonResult<Map<String, Object>> matchDetail(@PathVariable String matchId) {
        return Responses.ok(jobs.match(users.resolveCurrentUserId(), Data.uuid(matchId, "matchId")));
    }

    @GetMapping("/job-matches/{matchId}/items/{itemId}/evidence")
    public CommonResult<Map<String, Object>> matchEvidence(@PathVariable String matchId,
                                                            @PathVariable String itemId,
                                                            @RequestParam String skillCode) {
        return Responses.ok(jobs.matchEvidence(users.resolveCurrentUserId(), Data.uuid(matchId, "matchId"),
                Data.uuid(itemId, "itemId"), skillCode));
    }

    @GetMapping("/job-matches")
    public CommonResult<PageResult<Map<String, Object>>> matches(@RequestParam(required = false) Integer current,
                                                                  @RequestParam(required = false) Integer size) {
        return Responses.ok(jobs.matches(users.resolveCurrentUserId(), current, size));
    }
}
