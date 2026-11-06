package com.aijob.backend.task;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;

import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;

@Component
public class WebClientAgentClient implements AgentClient {

    private static final Map<String, String> PATHS = Map.ofEntries(
            Map.entry("RESUME_PARSE", "/internal/ai/v1/resumes/parse"),
            Map.entry("JD_ANALYZE", "/internal/ai/v1/jobs/analyze"),
            Map.entry("JOB_MATCH", "/internal/ai/v1/jobs/match"),
            Map.entry("JOB_SEARCH", "/internal/ai/v1/jobs/search"),
            Map.entry("LEARNING_PLAN", "/internal/ai/v1/learning/plans"),
            Map.entry("LEARNING_QUESTION", "/internal/ai/v1/learning/questions"),
            Map.entry("LEARNING_EVALUATE", "/internal/ai/v1/learning/evaluate"),
            Map.entry("INTERVIEW_TURN", "/internal/ai/v1/interviews/turn"),
            Map.entry("INTERVIEW_REPORT", "/internal/ai/v1/interviews/report"),
            Map.entry("GROWTH_ANALYZE", "/internal/ai/v1/growth/analyze"),
            Map.entry("RESUME_DIAGNOSE", "/internal/ai/v1/resumes/diagnose"),
            Map.entry("RESUME_OPTIMIZE", "/internal/ai/v1/resumes/optimize"),
            Map.entry("PROJECT_RECOMMEND", "/internal/ai/v1/projects/recommend"),
            Map.entry("PROJECT_PLAN", "/internal/ai/v1/projects/plans"));

    private final WebClient webClient;
    private final InternalJwtService jwtService;
    private final JsonService json;
    private final Duration timeout;

    public WebClientAgentClient(WebClient.Builder builder, InternalJwtService jwtService, JsonService json,
                                @Value("${app.agent.base-url}") String baseUrl,
                                @Value("${app.agent.connect-timeout}") Duration connectTimeout,
                                @Value("${app.agent.read-timeout}") Duration timeout) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connectTimeout.toMillis()));
        this.webClient = builder.baseUrl(baseUrl).clientConnector(new ReactorClientHttpConnector(httpClient)).build();
        this.jwtService = jwtService;
        this.json = json;
        this.timeout = timeout;
    }

    @Override
    public Map<String, Object> submit(AiTask task) {
        String path = PATHS.get(task.getOperation());
        if (path == null) {
            throw BusinessException.validation("operation", "不支持的 Agent operation");
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("taskId", task.getId());
        envelope.put("userId", task.getUserId());
        envelope.put("inputVersions", json.map(task.getInputVersionsJson()));
        envelope.put("deadlineAt", task.getDeadlineAt().toInstant(ZoneOffset.UTC));
        envelope.put("payload", json.map(task.getInputSnapshotJson()));
        return data(webClient.post().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.runToken(task.getId()))
                .header("Idempotency-Key", task.getIdempotencyKey())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(envelope)
                .retrieve().bodyToMono(Map.class).block(timeout));
    }

    @Override
    public Map<String, Object> findByTask(String taskId) {
        return data(webClient.get().uri(uri -> uri.path("/internal/ai/v1/runs").queryParam("taskId", taskId).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.runToken(taskId))
                .retrieve().bodyToMono(Map.class).block(timeout));
    }

    @Override
    public Map<String, Object> cancel(String runId, String taskId) {
        return data(webClient.post().uri("/internal/ai/v1/runs/{runId}/cancel", runId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.runToken(taskId))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .retrieve().bodyToMono(Map.class).block(timeout));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(Map<String, Object> response) {
        if (response == null || !(response.get("data") instanceof Map<?, ?>)) {
            throw BusinessException.dependencyUnavailable("Agent 返回无效响应");
        }
        return (Map<String, Object>) response.get("data");
    }

    @SuppressWarnings("unused")
    private String error(WebClientResponseException ex) {
        return ex.getStatusCode().value() + ":" + ex.getClass().getSimpleName();
    }
}