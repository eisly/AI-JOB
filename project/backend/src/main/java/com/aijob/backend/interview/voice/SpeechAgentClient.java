package com.aijob.backend.interview.voice;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import tools.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.task.InternalJwtService;

import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;

/**
 * 语音能力同步客户端：转写与合成均使用 ai:speech 短时令牌，不进入 ai_task 队列。
 */
@Component
public class SpeechAgentClient {

    private static final Logger log = LoggerFactory.getLogger(SpeechAgentClient.class);
    private static final String TRANSCRIBE_PATH = "/internal/ai/v1/speech/transcribe";
    private static final String SYNTHESIZE_PATH = "/internal/ai/v1/speech/synthesize";

    private final WebClient webClient;
    private final InternalJwtService jwtService;
    private final Duration transcribeTimeout;
    private final Duration synthesizeTimeout;

    public SpeechAgentClient(WebClient.Builder builder, InternalJwtService jwtService,
                             @Value("${app.agent.base-url}") String baseUrl,
                             @Value("${app.voice.transcribe-timeout:12s}") Duration transcribeTimeout,
                             @Value("${app.voice.synthesize-timeout:10s}") Duration synthesizeTimeout,
                             @Value("${app.agent.connect-timeout:5s}") Duration connectTimeout) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connectTimeout.toMillis()));
        this.webClient = builder.baseUrl(baseUrl).clientConnector(new ReactorClientHttpConnector(httpClient)).build();
        this.jwtService = jwtService;
        this.transcribeTimeout = transcribeTimeout;
        this.synthesizeTimeout = synthesizeTimeout;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> transcribe(byte[] audio, String mimeType, int durationMs, String requestId, String userId) {
        long started = System.nanoTime();
        try {
            MultipartBodyBuilder body = new MultipartBodyBuilder();
            body.part("audio", new ByteArrayResource(audio) {
                @Override
                public String getFilename() {
                    return "answer.audio";
                }
            }).contentType(MediaType.parseMediaType(mimeType));
            body.part("requestId", requestId);
            body.part("userId", userId);
            body.part("mimeType", mimeType);
            body.part("durationMs", String.valueOf(durationMs));
            body.part("language", "zh");
            Map<String, Object> response = webClient.post().uri(TRANSCRIBE_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.speechToken(requestId))
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(body.build()))
                    .retrieve().bodyToMono(Map.class).block(transcribeTimeout);
            Map<String, Object> data = data(response);
            log.info("voice asr request={} provider={} latency_ms={} text_chars={}",
                    requestId, data.get("provider"), elapsed(started), chars(data.get("text")));
            return data;
        } catch (RuntimeException ex) {
            log.warn("voice asr failed request={} error={} latency_ms={}", requestId, describe(ex), elapsed(started));
            throw unavailable(ex, "语音识别暂不可用");
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> synthesize(String text, String voice, String requestId, String userId) {
        long started = System.nanoTime();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("requestId", requestId);
        envelope.put("userId", userId);
        envelope.put("text", text);
        envelope.put("voice", voice);
        envelope.put("format", "mp3");
        try {
            Map<String, Object> response = webClient.post().uri(SYNTHESIZE_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.speechToken(requestId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(envelope)
                    .retrieve().bodyToMono(Map.class).block(synthesizeTimeout);
            Map<String, Object> data = data(response);
            log.info("voice tts request={} provider={} chars={} latency_ms={}",
                    requestId, data.get("provider"), data.get("chars"), elapsed(started));
            return data;
        } catch (RuntimeException ex) {
            log.warn("voice tts failed request={} error={} latency_ms={}", requestId, describe(ex), elapsed(started));
            throw unavailable(ex, "题目语音暂不可用");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(Map<String, Object> response) {
        if (response == null || !(response.get("data") instanceof Map<?, ?> data)) {
            throw BusinessException.dependencyUnavailable("语音服务返回无效响应");
        }
        return (Map<String, Object>) data;
    }

    static BusinessException unavailable(RuntimeException ex, String message) {
        if (ex instanceof BusinessException business) {
            return business;
        }
        if (ex instanceof WebClientResponseException response) {
            int status = response.getStatusCode().value();
            if (status == 413) return BusinessException.payloadTooLarge();
            if (status == 415) return BusinessException.unsupportedAudioType();
            if (status == 429) return BusinessException.rateLimited(30);
            String key = "";
            try {
                key = new ObjectMapper().readTree(response.getResponseBodyAsString())
                        .path("error").path("key").asText("");
            } catch (Exception ignored) {
                // Never log an upstream body: it can contain sensitive details.
            }
            if (status == 401 || status == 403 || "SPEECH_AUTH_FAILED".equals(key)) {
                return BusinessException.dependencyUnavailable("语音服务鉴权失败", Map.of("reason", "AUTH"));
            }
            if (status == 504 || "SPEECH_TIMEOUT".equals(key)) {
                return BusinessException.dependencyUnavailable(message, Map.of("reason", "TIMEOUT"));
            }
            return BusinessException.dependencyUnavailable(message, Map.of("reason", "UPSTREAM"));
        }
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof io.netty.handler.timeout.TimeoutException) {
                return BusinessException.dependencyUnavailable(message, Map.of("reason", "TIMEOUT"));
            }
        }
        return BusinessException.dependencyUnavailable(message, Map.of("reason", "CONNECTION"));
    }

    private static String describe(RuntimeException ex) {
        if (ex instanceof WebClientResponseException response) {
            return "HTTP " + response.getStatusCode().value();
        }
        return ex.getClass().getSimpleName();
    }

    private static int chars(Object text) {
        return text == null ? 0 : String.valueOf(text).length();
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
