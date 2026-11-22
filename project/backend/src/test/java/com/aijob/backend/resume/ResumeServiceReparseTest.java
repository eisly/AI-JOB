package com.aijob.backend.resume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import com.aijob.backend.ability.AbilityService;
import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.task.AiTask;
import com.aijob.backend.task.IdempotencyService;
import com.aijob.backend.task.TaskMapper;
import com.aijob.backend.task.TaskService;

import tools.jackson.databind.json.JsonMapper;

/**
 * reparse 参数与 OCR 任务路由的核心逻辑单测：
 * 覆盖「待确认版本重新解析、已确认版本拒绝、在途任务拦截、图片 PDF 走 RESUME_OCR」。
 */
class ResumeServiceReparseTest {

    private ResumeMapper mapper;
    private FileStorageService files;
    private TaskService tasks;
    private TaskMapper taskMapper;
    private ResumeService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ResumeMapper.class);
        files = mock(FileStorageService.class);
        tasks = mock(TaskService.class);
        taskMapper = mock(TaskMapper.class);
        JsonService json = new JsonService(JsonMapper.builder().build());
        ProfileService profiles = mock(ProfileService.class);
        service = new ResumeService(mapper, files, json, new ApiView(json), mock(IdempotencyService.class),
                tasks, taskMapper, profiles, mock(AbilityService.class), "http://127.0.0.1:8080");
        when(tasks.replay(any(), any(), any(), any(), any())).thenReturn(null);
        when(profiles.agentSkillCatalog(any())).thenReturn(List.of());
        when(tasks.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Consumer<String> placeholder = inv.getArgument(11);
                    placeholder.accept("task-1");
                    return ResponseEntity.ok().build();
                });
    }

    @Test
    void reparseParsedVersionCreatesParseTaskAndMarksParsing() {
        when(mapper.version("v", "u")).thenReturn(Map.of(
                "status", "PARSED", "file_name", "cv.pdf", "mime_type", "application/pdf",
                "size_bytes", 100, "extracted_text", "[page:1]\nJava MySQL"));
        when(taskMapper.latestResourceTask("RESUME_VERSION", "v")).thenReturn(null);

        service.parse("u", "v", "/route", "key", true);

        ArgumentCaptor<String> operation = ArgumentCaptor.forClass(String.class);
        verify(tasks).create(eq("u"), eq("POST"), eq("/route"), eq("key"), any(), operation.capture(),
                eq("RESUME_VERSION"), eq("v"), any(), any(), isNull(), any());
        assertEquals("RESUME_PARSE", operation.getValue());
        verify(mapper).updateStatus("v", "PARSING");
    }

    @Test
    void reparseConfirmedVersionIsRejected() {
        when(mapper.version("v", "u")).thenReturn(Map.of(
                "status", "CONFIRMED", "file_name", "cv.pdf", "mime_type", "application/pdf",
                "size_bytes", 100, "extracted_text", "[page:1]\nJava"));
        when(taskMapper.latestResourceTask("RESUME_VERSION", "v")).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.parse("u", "v", "/route", "key", true));
        verify(tasks, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void duplicateWhileInFlightIsRejected() {
        when(mapper.version("v", "u")).thenReturn(Map.of(
                "status", "PARSED", "file_name", "cv.pdf", "mime_type", "application/pdf",
                "size_bytes", 100, "extracted_text", "[page:1]\nJava"));
        AiTask latest = new AiTask();
        latest.setId("t-running");
        latest.setStatus("RUNNING");
        when(taskMapper.latestResourceTask("RESUME_VERSION", "v")).thenReturn(latest);
        assertThrows(BusinessException.class, () -> service.parse("u", "v", "/route", "key", true));
        verify(tasks, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void imagePdfWithoutTextRoutesToOcrTaskWithSignedDownloadUrl() {
        when(mapper.version("v", "u")).thenReturn(Map.of(
                "status", "UPLOADED", "file_name", "scan.pdf", "mime_type", "application/pdf",
                "size_bytes", 100, "extracted_text", "[page:1]\n[page:2]"));
        when(taskMapper.latestResourceTask("RESUME_VERSION", "v")).thenReturn(null);
        when(files.signature(eq("u"), eq("v"), any(Instant.class))).thenReturn("sig");

        service.parse("u", "v", "/route", "key", false);

        ArgumentCaptor<String> operation = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(tasks).create(eq("u"), eq("POST"), eq("/route"), eq("key"), any(), operation.capture(),
                eq("RESUME_VERSION"), eq("v"), any(), payload.capture(), isNull(), any());
        assertEquals("RESUME_OCR", operation.getValue());
        Map<String, Object> document = (Map<String, Object>) payload.getValue().get("document");
        String url = String.valueOf(document.get("downloadUrl"));
        assertTrue(url.startsWith("http://127.0.0.1:8080/api/v1/resume-versions/v/file"), "下载地址应指向签名端点: " + url);
        assertTrue(url.contains("owner=u"), "下载地址应携带 owner 参与验签: " + url);
        verify(mapper, never()).updateStatus(any(), any());
    }
}
