package com.aijob.backend.learning;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.aijob.backend.ability.AbilityService;
import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.task.TaskService;

import tools.jackson.databind.json.JsonMapper;

class PracticeServiceGradingTest {

    private final JsonService json = new JsonService(JsonMapper.builder().build());
    private final PracticeService service = new PracticeService(mock(LearningMapper.class),
            mock(LearningService.class), mock(TaskService.class), mock(ProfileService.class),
            mock(EvaluationService.class), mock(AbilityService.class), json, new ApiView(json));

    @Test
    void choiceGradingComparesAnswerKeyIgnoringCaseAndSpaces() {
        String key = "[\"B\"]";
        assertTrue(service.gradeChoice("b", key));
        assertTrue(service.gradeChoice(" B ", key));
        assertFalse(service.gradeChoice("A", key));
        assertFalse(service.gradeChoice("", key));
    }

    @Test
    void fillGradingRequiresEveryBlankInOrder() {
        String key = "[\"布隆过滤器\",\"空值缓存\"]";
        assertTrue(service.gradeFill(List.of("布隆过滤器", "空值缓存"), key));
        assertTrue(service.gradeFill(List.of(" 布隆过滤器 ", "空值缓存 "), key));
        assertFalse(service.gradeFill(List.of("空值缓存", "布隆过滤器"), key));
        assertFalse(service.gradeFill(List.of("布隆过滤器"), key));
    }

    @Test
    void gradingFailsWhenQuestionHasNoAnswerKey() {
        assertFalse(service.gradeChoice("A", null));
        assertFalse(service.gradeFill(List.of("A"), "[]"));
    }
}
