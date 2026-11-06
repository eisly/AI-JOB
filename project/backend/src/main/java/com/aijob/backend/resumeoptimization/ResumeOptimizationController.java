package com.aijob.backend.resumeoptimization;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
@RequestMapping("/api/v1/resume-optimizations")
public class ResumeOptimizationController {

    private final CurrentUserResolver users;
    private final ResumeOptimizationService optimizations;

    public ResumeOptimizationController(CurrentUserResolver users, ResumeOptimizationService optimizations) {
        this.users = users;
        this.optimizations = optimizations;
    }

    @PostMapping("/diagnoses")
    public ResponseEntity<CommonResult<Object>> diagnose(@RequestHeader("Idempotency-Key") String key,
                                                          @RequestBody Map<String, Object> body,
                                                          HttpServletRequest request) {
        return optimizations.diagnose(users.resolveCurrentUserId(), request.getRequestURI(), Data.idempotencyKey(key), body);
    }

    @GetMapping("/diagnoses")
    public CommonResult<PageResult<Map<String, Object>>> diagnoses(
            @RequestParam(required = false) String resumeVersionId,
            @RequestParam(required = false) Integer current, @RequestParam(required = false) Integer size) {
        String versionId = resumeVersionId == null ? null : Data.uuid(resumeVersionId, "resumeVersionId");
        return Responses.ok(optimizations.diagnoses(users.resolveCurrentUserId(), versionId, current, size));
    }

    @GetMapping("/diagnoses/{diagnosisId}")
    public CommonResult<Map<String, Object>> diagnosis(@PathVariable String diagnosisId) {
        return Responses.ok(optimizations.diagnosis(users.resolveCurrentUserId(), Data.uuid(diagnosisId, "diagnosisId")));
    }

    @PostMapping
    public ResponseEntity<CommonResult<Object>> optimize(@RequestHeader("Idempotency-Key") String key,
                                                          @RequestBody Map<String, Object> body,
                                                          HttpServletRequest request) {
        return optimizations.optimize(users.resolveCurrentUserId(), request.getRequestURI(), Data.idempotencyKey(key), body);
    }

    @GetMapping
    public CommonResult<PageResult<Map<String, Object>>> optimizations(
            @RequestParam(required = false) String resumeVersionId, @RequestParam(required = false) String jobId,
            @RequestParam(required = false) Integer current, @RequestParam(required = false) Integer size) {
        String versionId = resumeVersionId == null ? null : Data.uuid(resumeVersionId, "resumeVersionId");
        String normalizedJobId = jobId == null ? null : Data.uuid(jobId, "jobId");
        return Responses.ok(optimizations.optimizations(users.resolveCurrentUserId(), versionId, normalizedJobId, current, size));
    }

    @GetMapping("/{optimizationId}")
    public CommonResult<Map<String, Object>> optimization(@PathVariable String optimizationId) {
        return Responses.ok(optimizations.optimization(users.resolveCurrentUserId(), Data.uuid(optimizationId, "optimizationId")));
    }

    @PostMapping("/{optimizationId}/accept")
    public ResponseEntity<CommonResult<Object>> accept(@PathVariable String optimizationId,
                                                        @RequestHeader("Idempotency-Key") String key,
                                                        @RequestBody Map<String, Object> body,
                                                        HttpServletRequest request) {
        String id = Data.uuid(optimizationId, "optimizationId");
        return optimizations.accept(users.resolveCurrentUserId(), id, request.getRequestURI(), Data.idempotencyKey(key), body);
    }

    @GetMapping("/{optimizationId}/export")
    public ResponseEntity<byte[]> export(@PathVariable String optimizationId, @RequestParam String format) {
        ResumeOptimizationService.ExportContent content = optimizations.export(users.resolveCurrentUserId(),
                Data.uuid(optimizationId, "optimizationId"), format);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.mediaType()))
                .contentLength(content.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(content.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(content.bytes());
    }
}