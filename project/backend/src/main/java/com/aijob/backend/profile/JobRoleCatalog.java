package com.aijob.backend.profile;

import java.util.List;
import java.util.Set;

public final class JobRoleCatalog {

    private static final List<String> ORDERED = List.of(
            "JAVA_BACKEND", "PYTHON_BACKEND", "GO_BACKEND", "CPP_DEVELOPER", "NODEJS_BACKEND",
            "FULL_STACK", "MICROSERVICE_DEVELOPER",
            "AI_APPLICATION_DEVELOPER", "AI_AGENT_DEVELOPER", "LLM_APPLICATION_DEVELOPER",
            "RAG_APPLICATION_DEVELOPER", "LLM_ENGINEER", "AI_PLATFORM_DEVELOPER", "PROMPT_ENGINEER",
            "AI_ALGORITHM_ENGINEER",
            "FRONTEND", "REACT_DEVELOPER", "VUE_DEVELOPER", "ANDROID_DEVELOPER", "IOS_DEVELOPER",
            "FLUTTER_DEVELOPER",
            "ALGORITHM", "MACHINE_LEARNING_ENGINEER", "DEEP_LEARNING_ENGINEER", "NLP_ALGORITHM_ENGINEER",
            "COMPUTER_VISION_ENGINEER", "RECOMMENDATION_ALGORITHM", "DATA", "DATA_ANALYST", "DATA_ENGINEER",
            "TEST", "TEST_DEVELOPMENT_ENGINEER", "AUTOMATION_TEST_ENGINEER", "DEVOPS_ENGINEER", "SRE_ENGINEER",
            "CLOUD_ENGINEER", "SECURITY_ENGINEER",
            "EMBEDDED_ENGINEER", "DATABASE_DEVELOPER", "GAME_DEVELOPER", "BLOCKCHAIN_DEVELOPER",
            "TECHNICAL_PRODUCT_MANAGER", "SOLUTION_ENGINEER");

    private static final Set<String> VALUES = Set.copyOf(ORDERED);

    private JobRoleCatalog() {
    }

    public static Set<String> values() {
        return VALUES;
    }

    public static List<String> ordered() {
        return ORDERED;
    }

    public static boolean supports(String role) {
        return role != null && VALUES.contains(role);
    }
}