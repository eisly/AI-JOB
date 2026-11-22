package com.aijob.backend.interview;

/**
 * 面试题数计数规则。
 *
 * <p>MAIN_ONLY：历史语义，{@code question_limit} 表示主问题数量上限，追问不占额度。
 * <p>TOTAL_ASKED：新语义，主问题与追问共用 {@code question_limit} 作为总提问上限。
 */
public enum QuestionCountRule {

    MAIN_ONLY,
    TOTAL_ASKED;

    public static final QuestionCountRule DEFAULT = MAIN_ONLY;

    /**
     * 宽松解析：缺失、空串、{@code "null"}（{@code String.valueOf(null)} 的产物）与未知值
     * 一律按 MAIN_ONLY 处理，保证历史会话行为不变。
     */
    public static QuestionCountRule parse(Object raw) {
        if (raw == null) {
            return DEFAULT;
        }
        String value = String.valueOf(raw).strip();
        if (value.isEmpty() || "null".equalsIgnoreCase(value)) {
            return DEFAULT;
        }
        for (QuestionCountRule rule : values()) {
            if (rule.name().equalsIgnoreCase(value)) {
                return rule;
            }
        }
        return DEFAULT;
    }

    /** 剩余提问额度，始终不小于 0。 */
    public int remaining(int limit, int asked) {
        return Math.max(0, limit - asked);
    }
}