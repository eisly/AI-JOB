package com.aijob.backend.job;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class EligibilityService {

    private static final List<String> EDUCATION = List.of("HIGH_SCHOOL", "ASSOCIATE", "BACHELOR", "MASTER", "DOCTOR");

    @SuppressWarnings("unchecked")
    public Map<String, Object> evaluate(Map<String, Object> facts, Map<String, Object> requirements) {
        List<Map<String, Object>> checks = new ArrayList<>();
        Object education = requirements.get("educationMin");
        if (education != null && !EDUCATION.contains(String.valueOf(education))) {
            checks.add(item("education", "UNKNOWN", string(facts.get("education")), string(education),
                    "岗位学历要求无法可靠解析", quote(requirements, "educationMin")));
        } else {
            checks.add(check("education", facts.get("education"), education, requirements));
        }
        Object minYear = numberOrNull(requirements.get("graduationYearMin"));
        Object maxYear = numberOrNull(requirements.get("graduationYearMax"));
        if (requirements.get("graduationYearMin") != null && minYear == null
                || requirements.get("graduationYearMax") != null && maxYear == null) {
            checks.add(item("graduationYear", "UNKNOWN", string(facts.get("graduationYear")), null,
                    "岗位毕业年份要求无法可靠解析", quote(requirements, "graduationYearMin")));
        } else {
            checks.add(check("graduationYear", facts.get("graduationYear"),
                    minYear == null && maxYear == null ? null : new int[]{
                            minYear == null ? 1900 : ((Number) minYear).intValue(),
                            maxYear == null ? java.time.Year.now().getValue() + 10 : ((Number) maxYear).intValue()}, requirements));
        }
        Object months = numberOrNull(requirements.get("experienceMonthsMin"));
        if (requirements.get("experienceMonthsMin") != null && months == null) {
            checks.add(item("experienceMonths", "UNKNOWN", string(facts.get("experienceMonths")), null,
                    "岗位经验要求无法可靠解析", quote(requirements, "experienceMonthsMin")));
        } else {
            checks.add(check("experienceMonths", facts.get("experienceMonths"), months, requirements));
        }
        Map<String, Object> levels = (Map<String, Object>) requirements.getOrDefault("requiredSkillLevels", Map.of());
        if (!levels.isEmpty()) {
            checks.add(item("requiredSkillLevels", "UNKNOWN", null, jsonLike(levels),
                    "技能熟练度硬要求缺少可确定核验的确认事实", quote(requirements, "requiredSkillLevels")));
        }
        String status = checks.stream().anyMatch(item -> "FAIL".equals(item.get("result"))) ? "FAIL"
                : checks.stream().anyMatch(item -> "UNKNOWN".equals(item.get("result"))) ? "UNKNOWN" : "PASS";
        return Map.of("status", status, "ruleVersion", "eligibility-v1", "checks", checks);
    }

    private Map<String, Object> check(String field, Object actual, Object expected, Map<String, Object> requirements) {
        String quote = quote(requirements, field);
        if (expected == null) {
            return item(field, "PASS", string(actual), null, "未设要求", quote);
        }
        if (actual == null || ("education".equals(field) && !EDUCATION.contains(String.valueOf(actual)))
                || (!"education".equals(field) && !(actual instanceof Number))) {
            return item(field, "UNKNOWN", string(actual), string(expected), "缺少可用于核验的已确认事实", quote);
        }
        boolean pass = switch (field) {
            case "education" -> EDUCATION.indexOf(String.valueOf(actual)) >= EDUCATION.indexOf(String.valueOf(expected));
            case "graduationYear" -> {
                int year = ((Number) actual).intValue();
                int[] range = (int[]) expected;
                yield year >= range[0] && year <= range[1];
            }
            case "experienceMonths" -> ((Number) actual).intValue() >= ((Number) expected).intValue();
            default -> true;
        };
        return item(field, pass ? "PASS" : "FAIL", string(actual), string(expected),
                pass ? "符合已识别的明确规则" : "与岗位明确硬性要求冲突", quote);
    }

    private Map<String, Object> item(String field, String result, String actual, String expected,
                                     String reason, String quote) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("field", field);
        item.put("result", result);
        item.put("actual", actual);
        item.put("expected", expected);
        item.put("reason", reason);
        item.put("sourceQuote", quote);
        return item;
    }

    private String quote(Map<String, Object> requirements, String field) {
        Object quotes = requirements.get("quotes");
        String quoteField = switch (field) {
            case "education" -> "educationMin";
            case "graduationYear" -> "graduationYearMin";
            case "experienceMonths" -> "experienceMonthsMin";
            default -> field;
        };
        if (quotes instanceof Map<?, ?> map) {
            Object value = map.get(quoteField);
            return value == null ? null : String.valueOf(value);
        }
        return null;
    }

    private Object numberOrNull(Object value) {
        return value instanceof Number ? value : null;
    }

    private String string(Object value) {
        if (value instanceof int[] range) {
            return range[0] + ".." + range[1];
        }
        return value == null ? null : String.valueOf(value);
    }

    private String jsonLike(Map<String, Object> value) {
        return value.toString();
    }
}
