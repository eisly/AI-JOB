package com.aijob.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/career_business_test?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                "spring.flyway.enabled=true",
                "app.session.secure=false"
        })
class AuthFlowIntegrationTest {

    private static final String PASSWORD = "StrongPass_123";
    private static final String WRONG_PASSWORD = "WrongPass_999";
    private static final String FRONTEND_ORIGIN = "http://localhost:5173";

    @LocalServerPort
    private int port;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpClient client;

    private record Resp(int status, String body, List<String> setCookies, String csrfHeader, String retryAfter) {
        JsonNode json(ObjectMapper mapper) throws Exception {
            return mapper.readTree(body);
        }
    }

    private record Session(String email, String accessToken, String refreshToken, String csrfToken) {
    }

    @BeforeEach
    void setUp() {
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        Set<String> rateKeys = redisTemplate.keys("career:rate:login:*");
        if (rateKeys != null && !rateKeys.isEmpty()) {
            redisTemplate.delete(rateKeys);
        }
    }

    private Resp send(String method, String path, String json, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20));
        if (json != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        headers.forEach(builder::header);
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resp(response.statusCode(), response.body(),
                response.headers().allValues("set-cookie"),
                response.headers().firstValue("X-CSRF-Token").orElse(null),
                response.headers().firstValue("Retry-After").orElse(null));
    }

    private static String refreshTokenOf(Resp resp) {
        for (String value : resp.setCookies()) {
            if (value.startsWith("refresh_token=")) {
                int end = value.indexOf(';');
                return end < 0 ? value.substring("refresh_token=".length()) : value.substring("refresh_token=".length(), end);
            }
        }
        return null;
    }

    private String uniqueEmail() {
        return "it-" + UUID.randomUUID() + "@example.invalid";
    }

    private void register(String email, String password) throws Exception {
        Resp resp = send("POST", "/api/v1/auth/register",
                """
                        {"email":"%s","password":"%s","nickname":"集成测试"}
                        """.formatted(email, password), Map.of());
        assertThat(resp.status()).isEqualTo(200);
    }

    private Session registerAndLogin() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        Resp resp = send("POST", "/api/v1/auth/login",
                """
                        {"email":"%s","password":"%s"}
                        """.formatted(email, PASSWORD), Map.of());
        assertThat(resp.status()).isEqualTo(200);
        JsonNode node = resp.json(objectMapper);
        assertThat(node.at("/code").asInt()).isEqualTo(200);
        assertThat(node.at("/data/accessToken").asText()).isNotBlank();
        assertThat(node.at("/data/expiresIn").asLong()).isEqualTo(900);
        assertThat(resp.csrfHeader()).isNotBlank();
        String refreshToken = refreshTokenOf(resp);
        assertThat(refreshToken).isNotBlank();
        return new Session(email, node.at("/data/accessToken").asText(), refreshToken, resp.csrfHeader());
    }

    private static Map<String, String> withCookie(String refreshToken) {
        return Map.of("Cookie", "refresh_token=" + refreshToken, "Origin", FRONTEND_ORIGIN);
    }

    @Test
    void registersNewAccountAndRejectsDuplicates() throws Exception {
        String email = uniqueEmail();
        Resp first = send("POST", "/api/v1/auth/register",
                """
                        {"email":"%s","password":"%s","nickname":"张同学"}
                        """.formatted(email, PASSWORD), Map.of());
        assertThat(first.status()).isEqualTo(200);
        JsonNode node = first.json(objectMapper);
        assertThat(node.at("/code").asInt()).isEqualTo(200);
        assertThat(node.at("/data/email").asText()).isEqualTo(email);
        assertThat(node.at("/data/role").asText()).isEqualTo("USER");
        assertThat(node.at("/traceId").asText()).isNotBlank();

        Resp duplicate = send("POST", "/api/v1/auth/register",
                """
                        {"email":"%s","password":"%s","nickname":"重复"}
                        """.formatted(email, PASSWORD), Map.of());
        assertThat(duplicate.status()).isEqualTo(409);
        JsonNode duplicateNode = duplicate.json(objectMapper);
        assertThat(duplicateNode.at("/code").asInt()).isEqualTo(40907);
        assertThat(duplicateNode.at("/error/key").asText()).isEqualTo("EMAIL_ALREADY_EXISTS");
    }

    @Test
    void rejectsWeakPasswordWithDedicatedCode() throws Exception {
        Resp resp = send("POST", "/api/v1/auth/register",
                """
                        {"email":"%s","password":"short","nickname":"弱密码"}
                        """.formatted(uniqueEmail()), Map.of());
        assertThat(resp.status()).isEqualTo(400);
        JsonNode node = resp.json(objectMapper);
        assertThat(node.at("/code").asInt()).isEqualTo(40002);
        assertThat(node.at("/error/key").asText()).isEqualTo("WEAK_PASSWORD");
    }

    @Test
    void rejectsWrongPasswordWithUnifiedMessage() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        Resp resp = send("POST", "/api/v1/auth/login",
                """
                        {"email":"%s","password":"%s"}
                        """.formatted(email, WRONG_PASSWORD), Map.of());
        assertThat(resp.status()).isEqualTo(401);
        JsonNode node = resp.json(objectMapper);
        assertThat(node.at("/code").asInt()).isEqualTo(40101);
        assertThat(node.at("/msg").asText()).isEqualTo("邮箱或密码错误");
    }

    @Test
    void currentUserRequiresBearerToken() throws Exception {
        Resp anonymous = send("GET", "/api/v1/users/me", null, Map.of());
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.json(objectMapper).at("/code").asInt()).isEqualTo(40101);

        Session session = registerAndLogin();
        Resp me = send("GET", "/api/v1/users/me", null,
                Map.of("Authorization", "Bearer " + session.accessToken()));
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.json(objectMapper).at("/data/email").asText()).isEqualTo(session.email());
    }

    @Test
    void updatesNickname() throws Exception {
        Session session = registerAndLogin();
        Resp resp = send("PATCH", "/api/v1/users/me",
                """
                        {"nickname":"改名后"}
                        """, Map.of("Authorization", "Bearer " + session.accessToken()));
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.json(objectMapper).at("/data/nickname").asText()).isEqualTo("改名后");
    }

    @Test
    void rotatesRefreshTokenAndRevokesFamilyOnReplay() throws Exception {
        Session session = registerAndLogin();

        Resp refreshed = send("POST", "/api/v1/auth/refresh", "{}", Map.of(
                "Origin", FRONTEND_ORIGIN,
                "X-CSRF-Token", session.csrfToken(),
                "Cookie", "refresh_token=" + session.refreshToken()));
        assertThat(refreshed.status()).isEqualTo(200);
        String rotatedRefresh = refreshTokenOf(refreshed);
        String rotatedCsrf = refreshed.csrfHeader();
        assertThat(rotatedRefresh).isNotBlank().isNotEqualTo(session.refreshToken());
        assertThat(rotatedCsrf).isNotBlank();

        Resp replay = send("POST", "/api/v1/auth/refresh", "{}", Map.of(
                "Origin", FRONTEND_ORIGIN,
                "X-CSRF-Token", session.csrfToken(),
                "Cookie", "refresh_token=" + session.refreshToken()));
        assertThat(replay.status()).isEqualTo(401);

        Resp afterFamilyRevoked = send("POST", "/api/v1/auth/refresh", "{}", Map.of(
                "Origin", FRONTEND_ORIGIN,
                "X-CSRF-Token", rotatedCsrf,
                "Cookie", "refresh_token=" + rotatedRefresh));
        assertThat(afterFamilyRevoked.status()).isEqualTo(401);
    }

    @Test
    void rejectsRefreshWithWrongCsrfToken() throws Exception {
        Session session = registerAndLogin();
        Resp resp = send("POST", "/api/v1/auth/refresh", "{}", Map.of(
                "Origin", FRONTEND_ORIGIN,
                "X-CSRF-Token", "not-the-token",
                "Cookie", "refresh_token=" + session.refreshToken()));
        assertThat(resp.status()).isEqualTo(401);
    }

    @Test
    void logoutRevokesAccessTokenAndRefreshSession() throws Exception {
        Session session = registerAndLogin();
        Resp logout = send("POST", "/api/v1/auth/logout", "{}", Map.of(
                "Authorization", "Bearer " + session.accessToken(),
                "Origin", FRONTEND_ORIGIN,
                "X-CSRF-Token", session.csrfToken(),
                "Cookie", "refresh_token=" + session.refreshToken()));
        assertThat(logout.status()).isEqualTo(200);
        assertThat(logout.json(objectMapper).at("/data").asBoolean()).isTrue();
        assertThat(logout.setCookies().stream().anyMatch(value -> value.startsWith("refresh_token=;"))).isTrue();

        Resp me = send("GET", "/api/v1/users/me", null,
                Map.of("Authorization", "Bearer " + session.accessToken()));
        assertThat(me.status()).isEqualTo(401);

        Resp refresh = send("POST", "/api/v1/auth/refresh", "{}", Map.of(
                "Origin", FRONTEND_ORIGIN,
                "X-CSRF-Token", session.csrfToken(),
                "Cookie", "refresh_token=" + session.refreshToken()));
        assertThat(refresh.status()).isEqualTo(401);
    }

    @Test
    void issuesCsrfTokenFromRefreshCookie() throws Exception {
        Session session = registerAndLogin();
        Resp resp = send("GET", "/api/v1/auth/csrf", null, withCookie(session.refreshToken()));
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.csrfHeader()).isNotBlank();
        assertThat(resp.json(objectMapper).at("/data/csrfToken").asText()).isNotBlank();
    }

    @Test
    void rateLimitsRepeatedLoginFailures() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Resp resp = send("POST", "/api/v1/auth/login",
                    """
                            {"email":"%s","password":"%s"}
                            """.formatted(email, WRONG_PASSWORD), Map.of());
            statuses.add(resp.status());
        }
        assertThat(statuses.subList(0, 5)).containsOnly(401);
        assertThat(statuses.get(5)).isEqualTo(429);
        Resp limited = send("POST", "/api/v1/auth/login",
                """
                        {"email":"%s","password":"%s"}
                        """.formatted(email, WRONG_PASSWORD), Map.of());
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.json(objectMapper).at("/code").asInt()).isEqualTo(42901);
    }

    @Test
    void resetsPasswordWithVerificationCodeAndKicksSessions() throws Exception {
        Session session = registerAndLogin();
        String email = session.email();

        Resp forgot = send("POST", "/api/v1/auth/password/forgot",
                """
                        {"email":"%s"}
                        """.formatted(email), Map.of());
        assertThat(forgot.status()).isEqualTo(200);

        String code = redisTemplate.opsForValue().get("career:auth:code:" + email);
        assertThat(code).isNotNull();

        String newPassword = "BrandNewPass_456";
        Resp reset = send("POST", "/api/v1/auth/password/reset",
                """
                        {"email":"%s","code":"%s","newPassword":"%s"}
                        """.formatted(email, code, newPassword), Map.of());
        assertThat(reset.status()).isEqualTo(200);

        Resp oldToken = send("GET", "/api/v1/users/me", null,
                Map.of("Authorization", "Bearer " + session.accessToken()));
        assertThat(oldToken.status()).isEqualTo(401);

        Resp loginWithNewPassword = send("POST", "/api/v1/auth/login",
                """
                        {"email":"%s","password":"%s"}
                        """.formatted(email, newPassword), Map.of());
        assertThat(loginWithNewPassword.status()).isEqualTo(200);
    }

    @Test
    void doesNotRevealUnknownEmailOnForgotPassword() throws Exception {
        Resp resp = send("POST", "/api/v1/auth/password/forgot",
                """
                        {"email":"%s"}
                        """.formatted(uniqueEmail()), Map.of());
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.json(objectMapper).at("/data").asBoolean()).isTrue();
    }

    @Test
    void includesRetryAfterWhenResetCodeIsCoolingDown() throws Exception {
        String email = uniqueEmail();
        Resp first = send("POST", "/api/v1/auth/password/forgot",
                """
                        {"email":"%s"}
                        """.formatted(email), Map.of());
        assertThat(first.status()).isEqualTo(200);

        Resp second = send("POST", "/api/v1/auth/password/forgot",
                """
                        {"email":"%s"}
                        """.formatted(email), Map.of());
        assertThat(second.status()).isEqualTo(429);
        JsonNode node = second.json(objectMapper);
        assertThat(node.at("/code").asInt()).isEqualTo(42902);
        assertThat(node.at("/error/key").asText()).isEqualTo("CODE_SEND_TOO_FREQUENT");
        assertThat(second.retryAfter()).isNotBlank();
        assertThat(Long.parseLong(second.retryAfter())).isBetween(1L, 60L);
    }

    @Test
    void rejectsInvalidVerificationCode() throws Exception {
        Session session = registerAndLogin();
        Resp resp = send("POST", "/api/v1/auth/password/reset",
                """
                        {"email":"%s","code":"000000","newPassword":"BrandNewPass_456"}
                        """.formatted(session.email()), Map.of());
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.json(objectMapper).at("/code").asInt()).isEqualTo(40003);
    }

    @Test
    void rejectsRefreshFromUntrustedOrigin() throws Exception {
        Session session = registerAndLogin();
        Resp resp = send("POST", "/api/v1/auth/refresh", "{}", Map.of(
                "Origin", "http://evil.example",
                "X-CSRF-Token", session.csrfToken(),
                "Cookie", "refresh_token=" + session.refreshToken()));
        assertThat(resp.status()).isEqualTo(403);
        assertThat(resp.json(objectMapper).at("/code").asInt()).isEqualTo(40301);
    }

    @Test
    void rejectsCsrfRequestWithoutOriginOrReferer() throws Exception {
        Session session = registerAndLogin();
        Resp resp = send("GET", "/api/v1/auth/csrf", null,
                Map.of("Cookie", "refresh_token=" + session.refreshToken()));
        assertThat(resp.status()).isEqualTo(403);
        assertThat(resp.json(objectMapper).at("/code").asInt()).isEqualTo(40301);
    }

    @Test
    void usersEndpointsRequireAuthentication() throws Exception {
        Resp list = send("GET", "/api/v1/users", null, Map.of());
        assertThat(list.status()).isEqualTo(401);
        assertThat(list.json(objectMapper).at("/code").asInt()).isEqualTo(40101);

        Resp detail = send("GET", "/api/v1/users/10000000-0000-4000-8000-000000000001", null, Map.of());
        assertThat(detail.status()).isEqualTo(401);
        assertThat(detail.json(objectMapper).at("/code").asInt()).isEqualTo(40101);

        Session session = registerAndLogin();
        Resp forbiddenList = send("GET", "/api/v1/users", null,
                Map.of("Authorization", "Bearer " + session.accessToken()));
        assertThat(forbiddenList.status()).isEqualTo(403);
        assertThat(forbiddenList.json(objectMapper).at("/code").asInt()).isEqualTo(40301);

        Resp forbiddenDetail = send("GET", "/api/v1/users/10000000-0000-4000-8000-000000000001", null,
                Map.of("Authorization", "Bearer " + session.accessToken()));
        assertThat(forbiddenDetail.status()).isEqualTo(403);
        assertThat(forbiddenDetail.json(objectMapper).at("/code").asInt()).isEqualTo(40301);
    }
    @Test
    void loginTokenAccessesProfileAndApplicationsWithoutTrustingUserHeader() throws Exception {
        Session session = registerAndLogin();
        Map<String, String> auth = Map.of("Authorization", "Bearer " + session.accessToken());
        Resp profile = send("GET", "/api/v1/profile", null, auth);
        assertThat(profile.status()).isEqualTo(200);
        long version = profile.json(objectMapper).at("/data/profileVersion").asLong();
        Resp updated = send("PUT", "/api/v1/profile/preferences", """
                {"expectedProfileVersion":%d,"targetRole":"AI_AGENT_DEVELOPER","candidateType":"CAMPUS",
                 "cities":["330100"],"minMonthlySalaryCents":null,"maxMonthlySalaryCents":null}
                """.formatted(version), auth);
        assertThat(updated.status()).withFailMessage(updated.body()).isEqualTo(200);
        assertThat(updated.json(objectMapper).at("/data/targetRole").asText()).isEqualTo("AI_AGENT_DEVELOPER");
        assertThat(send("GET", "/api/v1/applications", null, auth).status()).isEqualTo(200);
        Resp forged = send("GET", "/api/v1/profile", null,
                Map.of("X-User-Id", "10000000-0000-4000-8000-00000000000a"));
        assertThat(forged.status()).isEqualTo(401);
    }

}