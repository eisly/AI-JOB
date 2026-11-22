package com.aijob.backend.project;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.job.JobMapper;
import com.aijob.backend.job.JobService;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.task.IdempotencyService;
import com.aijob.backend.task.TaskService;

import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class ProjectServiceTechStackKeywordsTest {

    private static final String ROUTE = "/api/v1/projects/recommendations";

    private final JsonService json = new JsonService(JsonMapper.builder().build());
    private final TaskService tasks = mock(TaskService.class);
    private final ProfileService profiles = mock(ProfileService.class);
    private final ProjectMapper mapper = mock(ProjectMapper.class);

    private ProjectService service() {
        when(profiles.get("user")).thenReturn(Map.of("profileVersion", 3L));
        return new ProjectService(mapper, mock(JobMapper.class), mock(JobService.class),
                profiles, tasks, mock(IdempotencyService.class), json, new ApiView(json),
                new ProjectProgressService());
    }

    @Test
    void recommendTrimsAndDedupesTechStackKeywordsIntoAgentPayload() {
        service().recommend("user", ROUTE, "key",
                body(List.of(" LangGraph ", "langgraph", "FastAPI")));

        assertEquals(List.of("LangGraph", "FastAPI"), capturedPayload().get("techStackKeywords"));
    }

    @Test
    void recommendKeepsExistingFlowWhenTechStackKeywordsOmitted() {
        service().recommend("user", ROUTE, "key",
                body(null));

        assertEquals(List.of(), capturedPayload().get("techStackKeywords"));
    }

    @Test
    void recommendRejectsNonArrayTechStackKeywords() {
        Map<String, Object> body = body(null);
        body.put("techStackKeywords", "LangGraph");

        assertThrows(BusinessException.class, () -> service().recommend("user", ROUTE, "key", body));
    }

    @Test
    void recommendRejectsBlankTechStackKeyword() {
        assertThrows(BusinessException.class, () -> service().recommend("user", ROUTE, "key",
                body(List.of("LangGraph", "   "))));
    }

    @Test
    void recommendRejectsTooManyTechStackKeywords() {
        assertThrows(BusinessException.class, () -> service().recommend("user", ROUTE, "key",
                body(List.of("a", "b", "c", "d", "e", "f"))));
        verify(tasks, never()).create(anyString(), anyString(), anyString(), anyString(), any(), anyString(),
                anyString(), anyString(), anyMap(), anyMap(), any());
    }

    @Test
    void recommendRejectsOverlongTechStackKeyword() {
        assertThrows(BusinessException.class, () -> service().recommend("user", ROUTE, "key",
                body(List.of("x".repeat(65)))));
    }

    @Test
    void continuationUsesOriginalFiltersAndSavedCursor() {
        String rootId = "a21aaedb-2460-4400-a2ec-5866576c2701";
        ProjectService service = service();
        Map<String, Object> root = discoveryBatch(rootId, 0);
        when(mapper.lockRecommendation(rootId, "user")).thenReturn(root);
        when(mapper.discoveryInput("first-task", "user")).thenReturn(Map.of(
                "input_snapshot_json", "{\"query\":\"预约\",\"techStackKeywords\":[\"springboot\"],\"sources\":[\"GITHUB\"]}",
                "input_versions_json", "{\"profileVersion\":3}"));

        service.more("user", rootId, ROUTE, "key", Map.of("afterBatchId", rootId));

        Map<String, Object> payload = capturedPayload();
        assertEquals(1, payload.get("discoveryRound"));
        assertEquals("预约", payload.get("query"));
        assertEquals(List.of("springboot"), payload.get("techStackKeywords"));
        assertEquals(Map.of("GITHUB:0", 2), payload.get("searchCursor"));
        assertEquals(List.of("GITHUB:team/appointments"), payload.get("seenRepositoryKeys"));
    }

    @Test
    void repeatedContinuationReturnsExistingTask() {
        String rootId = "a21aaedb-2460-4400-a2ec-5866576c2701";
        ProjectService service = service();
        when(mapper.lockRecommendation(rootId, "user")).thenReturn(discoveryBatch(rootId, 0));
        when(mapper.continuationTask(rootId, "user")).thenReturn("second-task");
        when(tasks.detail("user", "second-task")).thenReturn(Map.of("id", "second-task"));

        var result = service.more("user", rootId, ROUTE, "different-key", Map.of("afterBatchId", rootId));

        assertEquals(202, result.getStatusCode().value());
        assertEquals(Map.of("id", "second-task"), result.getBody().getData());
        verify(tasks, never()).create(anyString(), anyString(), anyString(), anyString(), any(), anyString(),
                anyString(), anyString(), anyMap(), anyMap(), any());
    }

    @Test
    void reloadAggregatesReadyPagesAndRetainsPreviousResultsWhenNextFails() {
        String rootId = "a21aaedb-2460-4400-a2ec-5866576c2701";
        ProjectService service = service();
        Map<String, Object> first = discoveryBatch(rootId, 0);
        first.put("items_json", "[{\"id\":\"first\",\"stackEvidence\":[{\"status\":\"DOCUMENTED\"}]}]");
        Map<String, Object> second = discoveryBatch("second", 1);
        second.put("items_json", "[{\"id\":\"second\",\"stackEvidence\":[{\"status\":\"VERIFIED\"}]}]");
        when(mapper.recommendation(rootId, "user")).thenReturn(first);
        when(mapper.discoveryBatches(rootId, "user")).thenReturn(List.of(second,
                Map.of("status", "FAILED", "task_id", "third-task")));

        var result = service.recommendation("user", rootId);

        assertEquals("second", result.get("afterBatchId"));
        assertEquals("third-task", result.get("continuationTaskId"));
        assertEquals(true, result.get("hasMore"));
        assertTrue(String.valueOf(result.get("items")).indexOf("id=second")
                < String.valueOf(result.get("items")).indexOf("id=first"));
    }

    private Map<String, Object> discoveryBatch(String id, int round) {
        return new LinkedHashMap<>(Map.of("id", id, "discovery_round", round, "profile_version", 3L,
                "status", "READY", "task_id", "first-task", "search_state_json",
                "{\"cursor\":{\"GITHUB:0\":2},\"keys\":[\"GITHUB:team/appointments\"]}"));
    }

    private Map<String, Object> body(List<String> techStackKeywords) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skillCodes", List.of("JAVA"));
        body.put("maxDurationWeeks", 4);
        if (techStackKeywords != null) {
            body.put("techStackKeywords", techStackKeywords);
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedPayload() {
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(tasks).create(anyString(), anyString(), anyString(), anyString(), any(), anyString(), anyString(),
                anyString(), anyMap(), payload.capture(), any());
        return payload.getValue();
    }
}
