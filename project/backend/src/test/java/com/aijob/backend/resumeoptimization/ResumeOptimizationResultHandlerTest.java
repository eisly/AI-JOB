package com.aijob.backend.resumeoptimization;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.task.AiTask;

import tools.jackson.databind.json.JsonMapper;

class ResumeOptimizationResultHandlerTest {

    private ResumeOptimizationMapper mapper;
    private JsonService json;
    private ResumeOptimizationResultHandler handler;

    @BeforeEach
    void setUp() {
        mapper = mock(ResumeOptimizationMapper.class);
        json = new JsonService(JsonMapper.builder().build());
        handler = new ResumeOptimizationResultHandler(mapper, json);
    }

    @Test
    void diagnosisAssignsJavaIdsAndPersistsValidatedQuote() {
        AiTask task = task("RESUME_DIAGNOSE", "诊断原文：使用 Redis 缓存。");
        when(mapper.lockDiagnosis("resource", "user")).thenReturn(Map.of("id", "resource"));
        when(mapper.completeDiagnosis(eq("resource"), eq("task"), any())).thenReturn(1);
        Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("priority", "IMPORTANT");
        item.put("title", "补充量化结果");
        item.put("issue", "缺少结果");
        item.put("quote", "使用 Redis 缓存");
        item.put("rationale", "无法判断成效");
        item.put("suggestion", "补充真实指标");

        int version = handler.success(task, json.tree(Map.of("items", List.of(item))));

        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(mapper).completeDiagnosis(eq("resource"), eq("task"), saved.capture());
        List<Map<String, Object>> items = json.convert(json.parse(saved.getValue()), List.class);
        assertNotNull(items.get(0).get("id"));
        assertFalse(items.get(0).containsKey("diagnosisId"));
        org.junit.jupiter.api.Assertions.assertEquals(1, version);
    }

    @Test
    void optimizationRejectsOriginalTextOutsideAuthorizedDocument() {
        AiTask task = task("RESUME_OPTIMIZE", "仅包含真实经历。");
        when(mapper.lockOptimization("resource", "user")).thenReturn(Map.of("id", "resource"));
        Map<String, Object> section = Map.of("sectionKey", "work", "title", "工作经历",
                "originalText", "虚构工作经历", "suggestedText", "优化建议", "sourceQuotes", List.of());

        assertThrows(BusinessException.class, () -> handler.success(task,
                json.tree(Map.of("title", "简历", "sections", List.of(section)))));
        verify(mapper, never()).completeOptimization(any(), any(), any(), any());
    }

    @Test
    void optimizationRejectsSourceQuoteOutsideAuthorizedDocument() {
        AiTask task = task("RESUME_OPTIMIZE", "真实项目原文。");
        when(mapper.lockOptimization("resource", "user")).thenReturn(Map.of("id", "resource"));
        Map<String, Object> section = Map.of("sectionKey", "project", "title", "项目",
                "originalText", "真实项目原文", "suggestedText", "优化建议", "sourceQuotes", List.of("其他原文"));

        assertThrows(BusinessException.class, () -> handler.success(task,
                json.tree(Map.of("title", "简历", "sections", List.of(section)))));
        verify(mapper, never()).completeOptimization(any(), any(), any(), any());
    }

    private AiTask task(String operation, String text) {
        AiTask task = new AiTask();
        task.setId("task");
        task.setUserId("user");
        task.setResourceId("resource");
        task.setOperation(operation);
        task.setInputSnapshotJson(json.stringify(Map.of("document", Map.of("text", text))));
        return task;
    }
}