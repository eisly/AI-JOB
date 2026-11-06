package com.aijob.backend.learning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

import com.aijob.backend.common.BusinessException;

class EvaluationServiceTest {

    private final EvaluationService service = new EvaluationService();

    @Test
    void recomputesOverallScoreInsteadOfTrustingAgent() {
        Map<String, Object> evaluation = evaluation("VALID", 99);

        EvaluationService.EvaluationResult result = service.normalize(evaluation,
                List.of(Map.of("skillCode", "java", "score", 80, "confidence", 0.8, "sourceId", "answer")),
                List.of("java"), "answer");

        assertEquals("72.0", result.evaluation().get("overallScore").toString());
        assertEquals(1, result.skillScores().size());
    }

    @Test
    void invalidEvidenceDoesNotProduceScoredEvidence() {
        EvaluationService.EvaluationResult result = service.normalize(evaluation("INVALID", 0), List.of(),
                List.of("java"), "answer");

        org.junit.jupiter.api.Assertions.assertNull(result.evaluation().get("overallScore"));
        assertEquals(List.of(), result.skillScores());
    }

    @Test
    void rejectsNonFixedRubricWeight() {
        Map<String, Object> evaluation = evaluation("VALID", 70);
        List<Map<String, Object>> dimensions = (List<Map<String, Object>>) evaluation.get("dimensions");
        dimensions.get(0).put("weight", 0.7);

        assertThrows(BusinessException.class, () -> service.normalize(evaluation, List.of(), List.of("java"), "answer"));
    }

    private Map<String, Object> evaluation(String status, int claimedOverall) {
        List<Map<String, Object>> dimensions = new ArrayList<>();
        dimensions.add(dimension("correctness", 60, 0.5));
        dimensions.add(dimension("completeness", 80, 0.3));
        dimensions.add(dimension("expression", 90, 0.2));
        return new java.util.LinkedHashMap<>(Map.of("evidenceStatus", status, "rubricVersion", "technical-answer-v1",
                "overallScore", claimedOverall, "dimensions", dimensions));
    }

    private Map<String, Object> dimension(String key, int score, double weight) {
        return new java.util.LinkedHashMap<>(Map.of("key", key, "score", score, "weight", weight, "comment", "ok"));
    }
}