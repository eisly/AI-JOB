package com.aijob.backend.task;

import java.util.Set;

public final class AgentOperation {

    public static final Set<String> ALL = Set.of("RESUME_PARSE", "JD_ANALYZE", "JOB_MATCH", "LEARNING_PLAN",
            "LEARNING_QUESTION", "LEARNING_EVALUATE", "INTERVIEW_TURN", "INTERVIEW_REPORT", "GROWTH_ANALYZE",
            "RESUME_DIAGNOSE", "RESUME_OPTIMIZE", "PROJECT_RECOMMEND", "PROJECT_PLAN");

    private AgentOperation() {
    }
}