package com.aijob.backend.account;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/career_business_test?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                "spring.flyway.enabled=true",
                "app.session.secure=false",
                "app.sms.enabled=false"
        })
class AccountCompatibilityIntegrationTest extends AccountIntegrationTestSupport {

    @Test
    void loginResponseKeepsExistingFields() throws Exception {
        String email = "compat-" + java.util.UUID.randomUUID() + "@example.invalid";
        send("POST", "/api/v1/auth/register", """
                {"email":"%s","password":"%s","nickname":"兼容测试"}
                """.formatted(email, PASSWORD), Map.of());
        Resp login = send("POST", "/api/v1/auth/login", """
                {"email":"%s","password":"%s"}
                """.formatted(email, PASSWORD), Map.of());
        assertThat(login.status()).isEqualTo(200);

        JsonNode user = login.json(objectMapper).at("/data/user");
        assertThat(user.at("/id").asText()).isNotBlank();
        assertThat(user.at("/email").asText()).isEqualTo(email);
        assertThat(user.at("/nickname").asText()).isEqualTo("兼容测试");
        assertThat(user.at("/role").asText()).isEqualTo("USER");
        assertThat(user.at("/createdAt").isMissingNode()).isFalse();
        // 新增字段为可选：未设置时不会出现在响应中
        assertThat(user.at("/phoneMasked").isMissingNode()).isTrue();
    }

    @Test
    void usersAdminEndpointsRemainRestricted() throws Exception {
        Session session = registerAndLogin();
        Resp users = send("GET", "/api/v1/users", null, bearer(session, null));
        assertThat(users.status()).isEqualTo(403);
        assertThat(users.json(objectMapper).at("/error/key").asText()).isEqualTo("FORBIDDEN");
    }

    @Test
    void usersMeStillWorks() throws Exception {
        Session session = registerAndLogin();
        Resp me = send("GET", "/api/v1/users/me", null, bearer(session, null));
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.json(objectMapper).at("/data/email").asText()).isEqualTo(session.email());
    }
}