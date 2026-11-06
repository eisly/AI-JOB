package com.aijob.backend.learning;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.aijob.backend.ability.AbilityService;
import com.aijob.backend.common.BusinessException;

@Service
public class EvaluationService {

    private static final Map<String, BigDecimal> WEIGHTS = Map.of(
            "correctness", BigDecimal.valueOf(0.5), "completeness", BigDecimal.valueOf(0.3),
            "expression", BigDecimal.valueOf(0.2));

    @SuppressWarnings("unchecked")
    public EvaluationResult normalize(Map<String, Object> input, List<Map<String, Object>> skillScores,
                                      List<String> allowedSkills, String sourceId) {
        Map<String, Object> evaluation = new LinkedHashMap<>(input);
        String status = String.valueOf(evaluation.get("evidenceStatus"));
        if (!Set.of("VALID", "INSUFFICIENT", "INVALID").contains(status)) {
            throw BusinessException.aiSchema("evidenceStatus 非法");
        }
        String rubricVersion = String.valueOf(evaluation.get("rubricVersion"));
        if (!AbilityService.RUBRIC.equals(rubricVersion)) {
            throw BusinessException.aiSchema("评价量表版本不受支持");
        }
        List<Map<String, Object>> dimensions = new ArrayList<>();
        BigDecimal overall = null;
        if ("VALID".equals(status)) {
            List<Map<String, Object>> raw = (List<Map<String, Object>>) evaluation.get("dimensions");
            if (raw == null || raw.size() != 3) {
                throw BusinessException.aiSchema("有效评价必须包含固定三维评分");
            }
            Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
            for (Map<String, Object> dimension : raw) {
                byKey.put(String.valueOf(dimension.get("key")), dimension);
            }
            if (!byKey.keySet().equals(WEIGHTS.keySet())) {
                throw BusinessException.aiSchema("评价维度必须为 correctness/completeness/expression");
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (String key : WEIGHTS.keySet()) {
                Map<String, Object> dimension = new LinkedHashMap<>(byKey.get(key));
                BigDecimal score = decimal(dimension.get("score"));
                BigDecimal weight = decimal(dimension.get("weight"));
                range(score, BigDecimal.ZERO, BigDecimal.valueOf(100), "维度分数");
                if (weight.compareTo(WEIGHTS.get(key)) != 0) {
                    throw BusinessException.aiSchema("评价维度权重不符合固定量表");
                }
                dimension.put("weight", WEIGHTS.get(key));
                dimensions.add(dimension);
                sum = sum.add(score.multiply(WEIGHTS.get(key)));
            }
            overall = sum.setScale(1, RoundingMode.HALF_UP);
        }
        evaluation.put("overallScore", overall);
        evaluation.put("dimensions", dimensions);
        List<Map<String, Object>> normalizedScores = new ArrayList<>();
        if ("VALID".equals(status)) {
            for (Map<String, Object> raw : skillScores) {
                String skill = String.valueOf(raw.get("skillCode"));
                if (!allowedSkills.contains(skill) || !sourceId.equals(String.valueOf(raw.get("sourceId")))) {
                    throw BusinessException.aiSchema("技能评分来源或技能编码不属于当前回答");
                }
                BigDecimal score = decimal(raw.get("score"));
                BigDecimal confidence = decimal(raw.get("confidence"));
                range(score, BigDecimal.ZERO, BigDecimal.valueOf(100), "技能分数");
                range(confidence, BigDecimal.ZERO, BigDecimal.ONE, "confidence");
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("skillCode", skill);
                item.put("score", score.setScale(1, RoundingMode.HALF_UP));
                item.put("confidence", confidence);
                item.put("sourceId", sourceId);
                normalizedScores.add(item);
            }
        }
        return new EvaluationResult(evaluation, normalizedScores);
    }

    @SuppressWarnings("unchecked")
    public void validateRubric(Map<String, Object> rubric) {
        if (!AbilityService.RUBRIC.equals(String.valueOf(rubric.get("version")))) {
            throw BusinessException.aiSchema("题目量表版本不受支持");
        }
        List<Map<String, Object>> dimensions = (List<Map<String, Object>>) rubric.get("dimensions");
        if (dimensions == null || dimensions.size() != 3) {
            throw BusinessException.aiSchema("题目量表必须包含固定三维");
        }
        Map<String, BigDecimal> actual = new LinkedHashMap<>();
        for (Map<String, Object> dimension : dimensions) {
            actual.put(String.valueOf(dimension.get("key")), decimal(dimension.get("weight")));
        }
        if (!actual.keySet().equals(WEIGHTS.keySet())) {
            throw BusinessException.aiSchema("题目量表维度非法");
        }
        WEIGHTS.forEach((key, weight) -> {
            if (weight.compareTo(actual.get(key)) != 0) {
                throw BusinessException.aiSchema("题目量表权重非法");
            }
        });
    }

    private BigDecimal decimal(Object value) {
        if (!(value instanceof Number)) {
            throw BusinessException.aiSchema("评分必须为数值");
        }
        return new BigDecimal(String.valueOf(value));
    }

    private void range(BigDecimal value, BigDecimal min, BigDecimal max, String name) {
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw BusinessException.aiSchema(name + "超出有效范围");
        }
    }

    public record EvaluationResult(Map<String, Object> evaluation, List<Map<String, Object>> skillScores) {
    }
}