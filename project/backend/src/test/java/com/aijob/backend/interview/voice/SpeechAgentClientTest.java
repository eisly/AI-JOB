package com.aijob.backend.interview.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import com.sun.net.httpserver.HttpServer;
import com.aijob.backend.task.InternalJwtService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.WebClient;

class SpeechAgentClientTest {
    @Test void acceptsFullQuestionAudioLargerThanDefaultCodecBuffer() throws Exception {
        String audio = "A".repeat(600_000);
        byte[] response = ("{\"data\":{\"audioBase64\":\"" + audio + "\",\"mimeType\":\"audio/wav\"}}")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/ai/v1/speech/synthesize", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            InternalJwtService jwt = mock(InternalJwtService.class);
            when(jwt.speechToken("request")).thenReturn("test-token");
            SpeechAgentClient client = new SpeechAgentClient(WebClient.builder(), jwt,
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(2));
            assertEquals(audio, client.synthesize("题目", "test", "request", "test-user", "wav").get("audioBase64"));
        } finally {
            server.stop(0);
        }
    }

    private String reason(int status, String body) {
        var error = WebClientResponseException.create(status, "upstream", HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        return String.valueOf(SpeechAgentClient.unavailable(error, "unavailable").getDetails().get("reason"));
    }

    @Test void preservesProviderAuthFailureThrough502() {
        assertEquals("AUTH", reason(502, "{\"error\":{\"key\":\"SPEECH_AUTH_FAILED\"}}"));
    }
    @Test void distinguishesUpstreamFailureFromTimeout() {
        assertEquals("UPSTREAM", reason(503, "{\"error\":{\"key\":\"SPEECH_UNAVAILABLE\"}}"));
        assertEquals("UPSTREAM", reason(502, "not json"));
        assertEquals("TIMEOUT", reason(504, "{}"));
    }
    @Test void distinguishesConnectionFailureFromDeadline() {
        assertEquals("CONNECTION", SpeechAgentClient.unavailable(new RuntimeException("connect"), "error")
                .getDetails().get("reason"));
        assertEquals("TIMEOUT", SpeechAgentClient.unavailable(new RuntimeException(new TimeoutException()), "error")
                .getDetails().get("reason"));
    }
}
