package com.aijob.backend.resumeoptimization;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.task.AiTask;
import com.aijob.backend.task.TaskResultHandler;

import tools.jackson.databind.JsonNode;

@Component
public class ResumeOptimizationResultHandler implements TaskResultHandler {

    private final ResumeOptimizationMapper mapper;
    private final JsonService json;

    public ResumeOptimizationResultHandler(ResumeOptimizationMapper mapper, JsonService json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    public Set<String> operations() {
        return Set.of("RESUME_DIAGNOSE", "RESUME_OPTIMIZE");
    }

    @Override
    public int success(AiTask task, JsonNode result) {
        return "RESUME_DIAGNOSE".equals(task.getOperation()) ? diagnose(task, result) : optimize(task, result);
    }

    @Override
    public void failure(AiTask task, JsonNode error) {
        if ("RESUME_DIAGNOSE".equals(task.getOperation())) {
            mapper.failDiagnosis(task.getResourceId(), task.getId());
        } else {
            mapper.failOptimization(task.getResourceId(), task.getId());
        }
    }

    @Override
    public void prepareRetry(AiTask original, String newTaskId) {
        if ("RESUME_DIAGNOSE".equals(original.getOperation())) {
            mapper.retryDiagnosis(original.getResourceId(), newTaskId);
        } else {
            mapper.retryOptimization(original.getResourceId(), newTaskId);
        }
    }

    private int diagnose(AiTask task, JsonNode result) {
        if (mapper.lockDiagnosis(task.getResourceId(), task.getUserId()) == null) {
            throw BusinessException.notFound();
        }
        String source = sourceText(task);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object raw : array(json.map(result), "items", 1, 20)) {
            Map<String, Object> input = object(raw, "items");
            String priority = text(input, "priority", 16);
            if (!Set.of("IMPORTANT", "SUGGESTION", "NOTICE").contains(priority)) {
                throw BusinessException.aiSchema("诊断优先级非法");
            }
            String quote = text(input, "quote", 1000);
            requireSource(source, quote, "诊断 quote 不属于授权简历原文");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", UUID.randomUUID().toString());
            item.put("priority", priority);
            item.put("title", text(input, "title", 200));
            item.put("issue", text(input, "issue", 2000));
            item.put("quote", quote);
            item.put("rationale", text(input, "rationale", 2000));
            item.put("suggestion", text(input, "suggestion", 2000));
            items.add(item);
        }
        if (mapper.completeDiagnosis(task.getResourceId(), task.getId(), json.stringify(items)) == 0) {
            throw BusinessException.conflict("诊断状态已变化", "INVALID_STATE");
        }
        return 1;
    }

    private int optimize(AiTask task, JsonNode result) {
        if (mapper.lockOptimization(task.getResourceId(), task.getUserId()) == null) {
            throw BusinessException.notFound();
        }
        String source = sourceText(task);
        Map<String, Object> value = json.map(result);
        Set<String> keys = new HashSet<>();
        List<Map<String, Object>> sections = new ArrayList<>();
        for (Object raw : array(value, "sections", 1, 20)) {
            Map<String, Object> input = object(raw, "sections");
            String sectionKey = text(input, "sectionKey", 64);
            if (!keys.add(sectionKey)) {
                throw BusinessException.aiSchema("优化段落 sectionKey 重复");
            }
            String original = text(input, "originalText", 10000);
            requireSource(source, original, "优化 originalText 不属于授权简历原文");
            List<String> quotes = new ArrayList<>();
            Object rawQuotes = input.get("sourceQuotes");
            if (!(rawQuotes instanceof List<?> list) || list.size() > 10) {
                throw BusinessException.aiSchema("优化 sourceQuotes 必须为最多 10 项的数组");
            }
            for (Object rawQuote : list) {
                if (!(rawQuote instanceof String quote) || quote.isBlank()) {
                    throw BusinessException.aiSchema("优化 sourceQuotes 必须为非空字符串");
                }
                requireSource(source, quote, "优化 sourceQuotes 不属于授权简历原文");
                quotes.add(quote);
            }
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("sectionKey", sectionKey);
            section.put("title", text(input, "title", 200));
            section.put("originalText", original);
            section.put("suggestedText", text(input, "suggestedText", 10000));
            section.put("sourceQuotes", quotes);
            sections.add(section);
        }
        if (mapper.completeOptimization(task.getResourceId(), task.getId(), text(value, "title", 200),
                json.stringify(sections)) == 0) {
            throw BusinessException.conflict("优化稿状态已变化", "INVALID_STATE");
        }
        return 1;
    }

    private String sourceText(AiTask task) {
        Map<String, Object> payload = json.map(task.getInputSnapshotJson());
        Object document = payload.get("document");
        if (!(document instanceof Map<?, ?> map) || !(map.get("text") instanceof String text) || text.isBlank()) {
            throw BusinessException.aiSchema("任务缺少授权简历原文");
        }
        return text;
    }

    private void requireSource(String source, String quote, String message) {
        if (!source.contains(quote)) {
            throw BusinessException.aiSchema(message);
        }
    }

    private List<Object> array(Map<String, Object> value, String field, int min, int max) {
        if (!(value.get(field) instanceof List<?> list) || list.size() < min || list.size() > max) {
            throw BusinessException.aiSchema(field + " 数组长度必须为 " + min + ".." + max);
        }
        return new ArrayList<>(list);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?>)) {
            throw BusinessException.aiSchema(field + " 必须为对象");
        }
        return (Map<String, Object>) value;
    }

    private String text(Map<String, Object> value, String field, int max) {
        if (!(value.get(field) instanceof String text) || text.isBlank() || text.strip().length() > max) {
            throw BusinessException.aiSchema(field + " 必须为 1.." + max + " 字符字符串");
        }
        return text.strip();
    }
}