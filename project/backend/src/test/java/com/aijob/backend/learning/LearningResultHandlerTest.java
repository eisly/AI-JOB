package com.aijob.backend.learning;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aijob.backend.ability.AbilityService;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.task.AiTask;

import tools.jackson.databind.json.JsonMapper;

class LearningResultHandlerTest {

    private final JsonService json = new JsonService(JsonMapper.builder().build());
    private final LearningMapper mapper = mock(LearningMapper.class);
    private final LearningService learning = mock(LearningService.class);
    private final EvaluationService evaluations = mock(EvaluationService.class);
    private final AbilityService ability = mock(AbilityService.class);
    private final ProfileService profiles = mock(ProfileService.class);

    private final LearningResultHandler handler = new LearningResultHandler(mapper, learning, evaluations,
            ability, profiles, json);

    @Test
    void insufficientEvaluationDoesNotCompletePlanItem() {
        AiTask task = task("LEARNING_EVALUATE", "answer");
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("id", "answer");
        answer.put("status", "EVALUATING");
        answer.put("session_id", "session");
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("id", "session");
        session.put("plan_id", "plan");
        session.put("plan_item_id", "item");
        session.put("question_id", "question");
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("id", "question");
        question.put("skill_codes_json", "[\"JAVA\"]");
        question.put("reference_answer", "参考答案");
        Map<String, Object> evaluation = new LinkedHashMap<>();
        evaluation.put("evidenceStatus", "INSUFFICIENT");
        evaluation.put("overallScore", null);
        evaluation.put("modelVersion", "fake");
        evaluation.put("promptVersion", "answer-evaluate-v3");
        when(mapper.lockAnswer("answer", "user")).thenReturn(answer);
        when(mapper.lockSession("session", "user")).thenReturn(session);
        when(mapper.question("question")).thenReturn(question);
        when(evaluations.normalize(any(), any(), any(), anyString()))
                .thenReturn(new EvaluationService.EvaluationResult(evaluation, List.of()));
        when(mapper.evaluated(anyString(), anyString())).thenReturn(1);

        handler.success(task, json.parse("{\"evaluation\":{\"evidenceStatus\":\"INSUFFICIENT\"},\"skillScores\":[]}"));

        verify(learning, never()).completePractice(anyString(), anyString());
        org.mockito.Mockito.verifyNoInteractions(ability);
    }

    @Test
    void resumePlanRejectsFabricatedProjectQuote() {
        AiTask task = task("LEARNING_PLAN", "plan");
        task.setInputSnapshotJson(json.stringify(Map.of(
                "planSource", "RESUME",
                "allowedSkillCodes", List.of("JAVA"),
                "knowledgeRefs", List.of(),
                "resume", Map.of("projectsText", "项目经历：基于 Spring Boot 实现订单系统。"))));
        when(mapper.lockPlan("plan", "user")).thenReturn(plan());

        String items = """
                {"title":"计划","summary":"依据简历","items":[{"skillCode":"JAVA","title":"项目深挖","kind":"INTERVIEW",
                "category":"RESUME_PROJECT","objective":"o","reason":"r","method":"m","completionCriteria":"c",
                "evidenceStatus":"ASSESSED","evidenceQuote":"基于 Kubernetes 实现多活架构","estimatedMinutes":30,
                "resourceRefs":[]}]}
                """;

        BusinessException error = assertThrows(BusinessException.class,
                () -> handler.success(task, json.parse(items)));
        assertEquals(422, error.getHttpStatus());
        verify(mapper, never()).insertItem(anyString(), anyString(), anyInt(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString());
    }

    @Test
    void githubItemRejectsUrlOutsideCandidates() {
        AiTask task = task("LEARNING_PLAN", "plan");
        task.setInputSnapshotJson(json.stringify(Map.of(
                "planSource", "RESUME",
                "allowedSkillCodes", List.of("JAVA"),
                "knowledgeRefs", List.of(),
                "resume", Map.of("projectsText", "项目经历：基于 Spring Boot 实现订单系统。"))));
        when(mapper.lockPlan("plan", "user")).thenReturn(plan());

        String items = """
                {"title":"计划","summary":"依据简历","github":{"status":"AVAILABLE","fetchedAt":"2026-11-12T00:00:00Z",
                "candidates":[{"fullName":"spring-projects/spring-boot","htmlUrl":"https://github.com/spring-projects/spring-boot",
                "description":"Spring Boot","stargazersCount":70000,"source":"GITHUB_API","verifiedAt":"2026-11-12T00:00:00Z"}]},
                "items":[{"skillCode":"JAVA","title":"开源项目学习","kind":"READING","category":"GITHUB","objective":"o",
                "reason":"r","method":"m","completionCriteria":"c","evidenceStatus":"ASSESSED",
                "resourceLinks":[{"label":"fake","url":"https://github.com/evil/fake-repo"}],"estimatedMinutes":30,
                "resourceRefs":[]}]}
                """;

        BusinessException error = assertThrows(BusinessException.class,
                () -> handler.success(task, json.parse(items)));
        assertEquals("AI_SCHEMA_INVALID", error.getErrorKey());
    }

    private AiTask task(String operation, String resourceId) {
        AiTask task = new AiTask();
        task.setId("task-1");
        task.setUserId("user");
        task.setOperation(operation);
        task.setResourceId(resourceId);
        return task;
    }

    private Map<String, Object> plan() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("id", "plan");
        plan.put("status", "GENERATING");
        plan.put("daily_minutes", 30);
        plan.put("days", 7);
        plan.put("title", null);
        plan.put("basis_json", null);
        return plan;
    }

}