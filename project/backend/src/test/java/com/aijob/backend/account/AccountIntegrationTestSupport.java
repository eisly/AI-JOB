package com.aijob.backend.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账号模块集成测试基类：真实 HTTP + 真实 MySQL/Redis。
 */
abstract class AccountIntegrationTestSupport {

    protected static final String PASSWORD = "StrongPass_123";
    protected static final String FRONTEND_ORIGIN = "http://localhost:5173";

    @LocalServerPort
    protected int port;

    protected final ObjectMapper objectMapper = new ObjectMapper();
    protected HttpClient client;

    protected record Resp(int status, String body, List<String> setCookies, String csrfHeader,
                          String retryAfter, String etag) {
        JsonNode json(ObjectMapper mapper) throws Exception {
            return mapper.readTree(body);
        }
    }

    protected record Session(String email, String accessToken) {
    }

    @BeforeEach
    void initClient() {
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    protected Resp send(String method, String path, String json, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20));
        if (json != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        headers.forEach(builder::header);
        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resp(response.statusCode(), response.body(),
                response.headers().allValues("set-cookie"),
                response.headers().firstValue("X-CSRF-Token").orElse(null),
                response.headers().firstValue("Retry-After").orElse(null),
                response.headers().firstValue("ETag").orElse(null));
    }

    protected Resp sendRaw(String method, String path, byte[] body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20));
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(builder::header);
        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resp(response.statusCode(), response.body(),
                response.headers().allValues("set-cookie"),
                response.headers().firstValue("X-CSRF-Token").orElse(null),
                response.headers().firstValue("Retry-After").orElse(null),
                response.headers().firstValue("ETag").orElse(null));
    }

    protected Session registerAndLogin() throws Exception {
        String email = "it-" + UUID.randomUUID() + "@example.invalid";
        Resp register = send("POST", "/api/v1/auth/register",
                """
                        {"email":"%s","password":"%s","nickname":"集成测试"}
                        """.formatted(email, PASSWORD), Map.of());
        assertThat(register.status()).isEqualTo(200);

        Resp login = send("POST", "/api/v1/auth/login",
                """
                        {"email":"%s","password":"%s"}
                        """.formatted(email, PASSWORD), Map.of());
        assertThat(login.status()).isEqualTo(200);
        JsonNode node = login.json(objectMapper);
        assertThat(node.at("/data/accessToken").asText()).isNotBlank();
        return new Session(email, node.at("/data/accessToken").asText());
    }

    protected String accountCsrf(Session session) throws Exception {
        Resp resp = send("GET", "/api/v1/account/csrf", null,
                Map.of("Authorization", "Bearer " + session.accessToken(), "Origin", FRONTEND_ORIGIN));
        assertThat(resp.status()).isEqualTo(200);
        String token = resp.json(objectMapper).at("/data/csrfToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    protected static Map<String, String> bearer(Session session, String csrfToken) {
        return csrfToken == null
                ? Map.of("Authorization", "Bearer " + session.accessToken(), "Origin", FRONTEND_ORIGIN)
                : Map.of("Authorization", "Bearer " + session.accessToken(),
                "Origin", FRONTEND_ORIGIN, "X-CSRF-Token", csrfToken);
    }

    protected Resp uploadAvatar(Session session, String csrfToken, String fileName, String contentType, byte[] bytes)
            throws Exception {
        String boundary = "----AIJOBTest" + UUID.randomUUID();
        byte[] prefix = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[prefix.length + bytes.length + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(bytes, 0, body, prefix.length, bytes.length);
        System.arraycopy(suffix, 0, body, prefix.length + bytes.length, suffix.length);

        Map<String, String> headers = new java.util.HashMap<>(bearer(session, csrfToken));
        headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        return sendRaw("POST", "/api/v1/account/avatar", body, headers);
    }
}