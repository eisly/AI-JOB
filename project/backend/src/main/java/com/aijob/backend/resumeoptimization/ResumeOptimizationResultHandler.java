package com.aijob.backend.resumeoptimization;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.resume.FileStorageService;
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
        Snapshot snapshot = snapshot(task);
        Map<String, Object> assessment = assessment(result, snapshot);
        boolean usesRubric = ResumeAssessmentRules.CURRENT_VERSION.equals(assessment.get("ruleVersion"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object raw : array(json.map(result), "items", usesRubric ? 0 : 1, 20)) {
            Map<String, Object> input = object(raw, "items");
            String priority = text(input, "priority", 16);
            if (!Set.of("IMPORTANT", "SUGGESTION", "NOTICE").contains(priority)) {
                throw BusinessException.aiSchema("诊断优先级非法");
            }
            String quote = text(input, "quote", 1000);
            requireSource(snapshot.text(), quote, "诊断 quote 不属于授权简历原文");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", UUID.randomUUID().toString());
            item.put("priority", priority);
            if (usesRubric) {
                String dimensionCode = text(input, "dimensionCode", 64);
                if (ResumeAssessmentRules.dimension(dimensionCode) == null) {
                    throw BusinessException.aiSchema("诊断建议所属维度非法");
                }
                item.put("dimensionCode", dimensionCode);
                // Older in-flight results can still finish without this field.
                if (input.get("checkCode") != null) {
                    String checkCode = text(input, "checkCode", 64);
                    Map<?, ?> check = ResumeAssessmentRules.scoredCheck(assessment, dimensionCode, checkCode);
                    if (check.isEmpty() || check.get("score").equals(check.get("maxScore"))) {
                        throw BusinessException.aiSchema("诊断建议必须关联实际扣分项");
                    }
                    item.put("checkCode", checkCode);
                }
            }
            item.put("title", text(input, "title", 200));
            item.put("issue", text(input, "issue", 2000));
            item.put("quote", quote);
            item.put("quotes", optionalQuotes(input, snapshot.text(), "诊断补充 quote"));
            item.put("rationale", text(input, "rationale", 2000));
            item.put("impact", optionalText(input, "impact", 1000));
            item.put("suggestion", text(input, "suggestion", 2000));
            item.put("example", optionalText(input, "example", 2000));
            item.put("pendingVerification", optionalStringList(input, "pendingVerification", 5, 200));
            items.add(item);
        }
        if (mapper.completeDiagnosis(task.getResourceId(), task.getId(), json.stringify(items),
                json.stringify(assessment), snapshot.text(), snapshot.layoutVersion(),
                snapshot.readOrderVerified()) == 0) {
            throw BusinessException.conflict("诊断状态已变化", "INVALID_STATE");
        }
        return 1;
    }

    private Map<String, Object> assessment(JsonNode result, Snapshot snapshot) {
        Map<String, Object> value = json.map(result);
        if (!(value.get("assessment") instanceof Map<?, ?>)) {
            throw BusinessException.aiSchema("诊断结果缺少评分 assessment");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> assessment = (Map<String, Object>) value.get("assessment");
        String ruleVersion = text(assessment, "ruleVersion", 64);
        boolean usesRubric = ResumeAssessmentRules.CURRENT_VERSION.equals(ruleVersion);
        if (!ResumeAssessmentRules.supports(ruleVersion)
                || (snapshot.ruleVersion() == null ? usesRubric : !snapshot.ruleVersion().equals(ruleVersion))) {
            throw BusinessException.aiSchema("评分规则版本非法");
        }
        List<Object> dimensionList = array(assessment, "dimensions", 5, 5);
        Set<String> codes = new HashSet<>();
        int total = 0;
        List<Map<String, Object>> dimensions = new ArrayList<>();
        for (Object raw : dimensionList) {
            Map<String, Object> input = object(raw, "dimensions");
            String code = text(input, "code", 64);
            ResumeAssessmentRules.Dimension rule = ResumeAssessmentRules.dimension(code);
            if (rule == null) {
                throw BusinessException.aiSchema("评分维度非法: " + code);
            }
            if (!codes.add(code)) {
                throw BusinessException.aiSchema("评分维度重复: " + code);
            }
            Map<String, Object> dimension = new LinkedHashMap<>();
            int score;
            if (usesRubric) {
                List<Map<String, Object>> criteria = scoreCriteria(input, rule);
                dimension.put("criteria", criteria);
                score = criteria.stream().mapToInt(check -> (int) check.get("score")).sum();
            } else {
                int max = rule.maxScore(ruleVersion);
                if (!(input.get("score") instanceof Integer legacyScore) || legacyScore < 0 || legacyScore > max) {
                    throw BusinessException.aiSchema("评分 score 必须为 0.." + max + " 的整数");
                }
                score = legacyScore;
            }
            String quote = null;
            if (score > 0) {
                quote = text(input, "quote", 1000);
                requireSource(snapshot.text(), quote, "评分维度 " + code + " quote 不属于授权简历原文");
            }
            dimension.put("code", code);
            dimension.put("score", score);
            dimension.put("rationale", text(input, "rationale", 2000));
            dimension.put("quote", quote);
            dimension.put("quotes", optionalQuotes(input, snapshot.text(), "评分维度 " + code + " 补充 quote"));
            dimensions.add(dimension);
            total += score;
        }
        List<Object> strengthList = array(assessment, "strengths", 0, 10);
        List<Map<String, Object>> strengths = new ArrayList<>();
        for (Object raw : strengthList) {
            Map<String, Object> input = object(raw, "strengths");
            String quote = text(input, "quote", 1000);
            requireSource(snapshot.text(), quote, "优势 quote 不属于授权简历原文");
            Map<String, Object> strength = new LinkedHashMap<>();
            strength.put("title", text(input, "title", 200));
            strength.put("quote", quote);
            strength.put("quotes", optionalQuotes(input, snapshot.text(), "优势补充 quote"));
            strength.put("description", text(input, "description", 2000));
            strength.put("approach", optionalText(input, "approach", 1000));
            strength.put("contribution", optionalText(input, "contribution", 1000));
            strength.put("result", optionalText(input, "result", 1000));
            strengths.add(strength);
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("ruleVersion", ruleVersion);
        if (usesRubric) {
            normalized.put("summary", text(assessment, "summary", 600));
        }
        normalized.put("totalScore", total);
        normalized.put("dimensions", dimensions);
        normalized.put("strengths", strengths);
        normalized.put("assessedAt", Instant.now().toString());
        return normalized;
    }

    private List<Map<String, Object>> scoreCriteria(Map<String, Object> input, ResumeAssessmentRules.Dimension rule) {
        Map<String, Object> levels = object(input.get("levels"), "levels");
        if (levels.size() != rule.checks().size()) {
            throw BusinessException.aiSchema("评分点数量与规则不一致: " + rule.code());
        }
        List<Map<String, Object>> criteria = new ArrayList<>();
        for (ResumeAssessmentRules.Check check : rule.checks()) {
            if (!(levels.get(check.code()) instanceof Integer level) || level < 0 || level > 5) {
                throw BusinessException.aiSchema("评分点档位必须为 0..5 的整数: " + check.code());
            }
            criteria.add(Map.of("code", check.code(), "label", check.label(),
                    "score", level * check.maxScore() / 5, "maxScore", check.maxScore()));
        }
        return criteria;
    }

    private int optimize(AiTask task, JsonNode result) {
        if (mapper.lockOptimization(task.getResourceId(), task.getUserId()) == null) {
            throw BusinessException.notFound();
        }
        Snapshot snapshot = snapshot(task);
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
            requireSource(snapshot.text(), original, "优化 originalText 不属于授权简历原文");
            List<String> quotes = new ArrayList<>();
            Object rawQuotes = input.get("sourceQuotes");
            if (!(rawQuotes instanceof List<?> list) || list.size() > 10) {
                throw BusinessException.aiSchema("优化 sourceQuotes 必须为最多 10 项的数组");
            }
            for (Object rawQuote : list) {
                if (!(rawQuote instanceof String quote) || quote.isBlank()) {
                    throw BusinessException.aiSchema("优化 sourceQuotes 必须为非空字符串");
                }
                requireSource(snapshot.text(), quote, "优化 sourceQuotes 不属于授权简历原文");
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

    private Snapshot snapshot(AiTask task) {
        Map<String, Object> payload = json.map(task.getInputSnapshotJson());
        Object document = payload.get("document");
        if (!(document instanceof Map<?, ?> map) || !(map.get("text") instanceof String text) || text.isBlank()) {
            throw BusinessException.aiSchema("任务缺少授权简历原文");
        }
        String layoutVersion = map.get("textLayoutVersion") instanceof String layout && !layout.isBlank()
                ? layout
                : FileStorageService.LEGACY_LAYOUT_VERSION;
        String ruleVersion = payload.get("assessmentRules") instanceof Map<?, ?> rules
                && rules.get("ruleVersion") instanceof String version && ResumeAssessmentRules.supports(version)
                ? version : null;
        return new Snapshot(text, layoutVersion, truthy(map.get("readOrderVerified")), ruleVersion);
    }

    private List<String> optionalQuotes(Map<String, Object> input, String source, String message) {
        Object raw = input.get("quotes");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list) || list.size() > 2) {
            throw BusinessException.aiSchema("quotes 必须为最多 2 项的数组");
        }
        List<String> quotes = new ArrayList<>();
        for (Object element : list) {
            if (!(element instanceof String quote) || quote.isBlank() || quote.strip().length() > 1000) {
                throw BusinessException.aiSchema("quotes 必须为 1..1000 字符的非空字符串");
            }
            requireSource(source, quote.strip(), message);
            quotes.add(quote.strip());
        }
        return quotes;
    }

    private String optionalText(Map<String, Object> input, String field, int max) {
        Object raw = input.get(field);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String text)) {
            throw BusinessException.aiSchema(field + " 必须为字符串");
        }
        String stripped = text.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        if (stripped.length() > max) {
            throw BusinessException.aiSchema(field + " 长度不能超过 " + max);
        }
        return stripped;
    }

    private List<String> optionalStringList(Map<String, Object> input, String field, int limit, int max) {
        Object raw = input.get(field);
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list) || list.size() > limit) {
            throw BusinessException.aiSchema(field + " 必须为最多 " + limit + " 项的数组");
        }
        Set<String> values = new LinkedHashSet<>();
        for (Object element : list) {
            if (!(element instanceof String text) || text.isBlank() || text.strip().length() > max) {
                throw BusinessException.aiSchema(field + " 必须为 1.." + max + " 字符的非空字符串");
            }
            values.add(text.strip());
        }
        return new ArrayList<>(values);
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

    private boolean truthy(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }

    private record Snapshot(String text, String layoutVersion, boolean readOrderVerified, String ruleVersion) {
    }
}
