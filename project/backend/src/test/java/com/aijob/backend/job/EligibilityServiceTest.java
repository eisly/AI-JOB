package com.aijob.backend.job;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class EligibilityServiceTest {

    private final EligibilityService service = new EligibilityService();

    @Test
    void failsExplicitEducationConflictAndKeepsSourceQuote() {
        Map<String, Object> facts = facts("HIGH_SCHOOL", 2026, 12);
        Map<String, Object> requirements = requirements("BACHELOR");

        Map<String, Object> result = service.evaluate(facts, requirements);

        assertEquals("FAIL", result.get("status"));
        List<Map<String, Object>> checks = (List<Map<String, Object>>) result.get("checks");
        assertEquals("FAIL", checks.get(0).get("result"));
        assertEquals("本科及以上", checks.get(0).get("sourceQuote"));
    }

    @Test
    void missingConfirmedFactIsUnknownRatherThanPass() {
        Map<String, Object> result = service.evaluate(facts("BACHELOR", null, 12), requirements("BACHELOR"));

        assertEquals("UNKNOWN", result.get("status"));
        List<Map<String, Object>> checks = (List<Map<String, Object>>) result.get("checks");
        assertEquals("UNKNOWN", checks.get(1).get("result"));
    }

    @Test
    void ambiguousEducationRequirementIsUnknown() {
        Map<String, Object> requirements = requirements("未明确");

        Map<String, Object> result = service.evaluate(facts("DOCTOR", 2026, 120), requirements);

        assertEquals("UNKNOWN", result.get("status"));
    }

    @Test
    void requiredSkillLevelsCannotBeIgnored() {
        Map<String, Object> requirements = requirements("BACHELOR");
        requirements.put("requiredSkillLevels", Map.of("java", 70));

        Map<String, Object> result = service.evaluate(facts("MASTER", 2026, 120), requirements);

        assertEquals("UNKNOWN", result.get("status"));
    }

    private Map<String, Object> facts(String education, Integer year, Integer months) {
        Map<String, Object> facts = new HashMap<>();
        facts.put("education", education);
        facts.put("graduationYear", year);
        facts.put("experienceMonths", months);
        return facts;
    }

    private Map<String, Object> requirements(String education) {
        Map<String, Object> requirements = new HashMap<>();
        requirements.put("educationMin", education);
        requirements.put("graduationYearMin", 2025);
        requirements.put("graduationYearMax", 2027);
        requirements.put("experienceMonthsMin", 6);
        requirements.put("requiredSkillLevels", Map.of());
        requirements.put("quotes", Map.of("educationMin", "本科及以上", "graduationYearMin", "2025至2027届"));
        return requirements;
    }
}