package com.aijob.backend.interview.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

class SpeechAgentClientTest {
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
