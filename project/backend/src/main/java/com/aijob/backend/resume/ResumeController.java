package com.aijob.backend.resume;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.aijob.backend.common.CommonResult;
import com.aijob.backend.common.CurrentUserResolver;
import com.aijob.backend.common.Data;
import com.aijob.backend.common.PageResult;
import com.aijob.backend.common.Responses;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1")
public class ResumeController {

    private final CurrentUserResolver users;
    private final ResumeService resumes;

    public ResumeController(CurrentUserResolver users, ResumeService resumes) {
        this.users = users;
        this.resumes = resumes;
    }

    @PostMapping(value = "/resumes/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CommonResult<Object>> upload(@RequestPart("file") MultipartFile file,
                                                        @RequestParam(required = false) String title,
                                                        @RequestParam(required = false) String resumeId,
                                                        @RequestHeader("Idempotency-Key") String key,
                                                        HttpServletRequest request) {
        return resumes.upload(users.resolveCurrentUserId(), request.getRequestURI(), Data.idempotencyKey(key), file, title, resumeId);
    }

    @GetMapping("/resumes")
    public CommonResult<PageResult<Map<String, Object>>> list(@RequestParam(required = false) Integer current,
                                                               @RequestParam(required = false) Integer size) {
        return Responses.ok(resumes.list(users.resolveCurrentUserId(), current, size));
    }

    @GetMapping("/resumes/{resumeId}/versions")
    public CommonResult<List<Map<String, Object>>> versions(@PathVariable String resumeId) {
        return Responses.ok(resumes.versions(users.resolveCurrentUserId(), Data.uuid(resumeId, "resumeId")));
    }

    @DeleteMapping("/resumes/{resumeId}")
    public CommonResult<Boolean> delete(@PathVariable String resumeId) {
        return Responses.ok(resumes.delete(users.resolveCurrentUserId(), Data.uuid(resumeId, "resumeId")));
    }

    @GetMapping("/resume-versions/{versionId}")
    public CommonResult<Map<String, Object>> version(@PathVariable String versionId) {
        return Responses.ok(resumes.version(users.resolveCurrentUserId(), Data.uuid(versionId, "versionId")));
    }

    @GetMapping("/resume-versions/{versionId}/download")
    public CommonResult<Map<String, Object>> download(@PathVariable String versionId) {
        return Responses.ok(resumes.download(users.resolveCurrentUserId(), Data.uuid(versionId, "versionId")));
    }

    @GetMapping("/resume-versions/{versionId}/file")
    public ResponseEntity<org.springframework.core.io.Resource> file(@PathVariable String versionId,
                                                                      @RequestParam long expires,
                                                                      @RequestParam String signature) {
        String userId = users.resolveCurrentUserId();
        ResumeService.DownloadContent content = resumes.file(userId, Data.uuid(versionId, "versionId"), expires, signature);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(content.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString()).body(content.resource());
    }

    @PostMapping("/resume-versions/{versionId}/parse")
    public ResponseEntity<CommonResult<Object>> parse(@PathVariable String versionId,
                                                       @RequestHeader("Idempotency-Key") String key,
                                                       @RequestBody(required = false) Map<String, Object> body,
                                                       HttpServletRequest request) {
        String id = Data.uuid(versionId, "versionId");
        return resumes.parse(users.resolveCurrentUserId(), id, request.getRequestURI(), Data.idempotencyKey(key));
    }

    @PostMapping("/resume-versions/{versionId}/confirm")
    public ResponseEntity<CommonResult<Object>> confirm(@PathVariable String versionId,
                                                         @RequestHeader("Idempotency-Key") String key,
                                                         @RequestBody Map<String, Object> body,
                                                         HttpServletRequest request) {
        String id = Data.uuid(versionId, "versionId");
        return resumes.confirm(users.resolveCurrentUserId(), id, request.getRequestURI(), Data.idempotencyKey(key), body);
    }

    @PostMapping("/resume-versions/{versionId}/corrections")
    public ResponseEntity<CommonResult<Object>> correct(@PathVariable String versionId,
                                                         @RequestHeader("Idempotency-Key") String key,
                                                         @RequestBody Map<String, Object> body,
                                                         HttpServletRequest request) {
        String id = Data.uuid(versionId, "versionId");
        return resumes.correct(users.resolveCurrentUserId(), id, request.getRequestURI(), Data.idempotencyKey(key), body);
    }

    @GetMapping("/resume-versions/{versionId}/corrections")
    public CommonResult<List<Map<String, Object>>> corrections(@PathVariable String versionId) {
        return Responses.ok(resumes.factRevisions(users.resolveCurrentUserId(), Data.uuid(versionId, "versionId")));
    }
}
