package com.aijob.backend.resumeoptimization;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

final class ResumeAssessmentRules {

    static final String CURRENT_VERSION = "resume-quality-v3";

    private static final List<Dimension> DIMENSIONS = List.of(
            new Dimension("EXPERIENCE_PROJECTS", "经历与项目", 30, List.of(
                    new Check("problemContext", "问题与场景", 10),
                    new Check("personalRole", "个人职责", 10),
                    new Check("approach", "做法与取舍", 10),
                    new Check("outcomes", "成果与依据", 10))),
            new Dimension("SKILLS_EVIDENCE", "技能与证据", 20, List.of(
                    new Check("skillSpecificity", "技能描述的具体程度", 10),
                    new Check("appliedEvidence", "经历中的应用证据", 10))),
            new Dimension("CONTENT_COMPLETENESS", "内容完整性", 20, List.of(
                    new Check("background", "背景信息", 5),
                    new Check("experienceFields", "主要经历要素", 5),
                    new Check("context", "关键信息的上下文", 5))),
            new Dimension("STRUCTURE_CLARITY", "结构清晰度", 15, List.of(
                    new Check("sections", "章节分组", 5),
                    new Check("hierarchy", "条目层级", 5),
                    new Check("timeline", "经历时间线", 5))),
            new Dimension("EXPRESSION_PROFESSIONALISM", "表达专业性", 15, List.of(
                    new Check("conciseness", "表达具体简洁", 5),
                    new Check("consistency", "术语与格式一致", 5))));

    private static final Map<String, Dimension> BY_CODE = DIMENSIONS.stream()
            .collect(Collectors.toUnmodifiableMap(Dimension::code, Function.identity()));

    private ResumeAssessmentRules() {
    }

    static boolean supports(String version) {
        return CURRENT_VERSION.equals(version) || "resume-quality-v1".equals(version)
                || "resume-quality-v2".equals(version);
    }

    static Dimension dimension(String code) {
        return BY_CODE.get(code);
    }

    static Map<?, ?> scoredCheck(Map<String, Object> assessment, String dimensionCode, String checkCode) {
        if (!(assessment.get("dimensions") instanceof List<?> dimensions)) {
            return Map.of();
        }
        for (Object value : dimensions) {
            if (value instanceof Map<?, ?> dimension && dimensionCode.equals(dimension.get("code"))
                    && dimension.get("criteria") instanceof List<?> criteria) {
                for (Object raw : criteria) {
                    if (raw instanceof Map<?, ?> check && checkCode.equals(check.get("code"))) {
                        return check;
                    }
                }
            }
        }
        return Map.of();
    }

    static Map<String, Object> currentPayload() {
        return Map.of("ruleVersion", CURRENT_VERSION, "dimensions", DIMENSIONS.stream().map(dimension -> Map.of(
                "code", dimension.code(),
                "maxScore", dimension.maxScore(CURRENT_VERSION),
                "criteria", dimension.checks().stream().map(Check::label).collect(Collectors.joining("、")),
                "checks", dimension.checks().stream().map(check -> Map.of(
                        "code", check.code(), "label", check.label(), "maxScore", check.maxScore())).toList()
        )).toList());
    }

    record Dimension(String code, String label, int legacyMaxScore, List<Check> checks) {
        int maxScore(String version) {
            return CURRENT_VERSION.equals(version)
                    ? checks.stream().mapToInt(Check::maxScore).sum()
                    : legacyMaxScore;
        }
    }

    record Check(String code, String label, int maxScore) {
    }
}
