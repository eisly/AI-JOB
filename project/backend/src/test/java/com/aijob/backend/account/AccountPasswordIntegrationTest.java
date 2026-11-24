package com.aijob.backend.account;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/career_business_test?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                "spring.flyway.enabled=true",
                "app.session.secure=false",
                "app.sms.enabled=true",
                "app.sms.expose-code-in-response=true"
        })
@TestPropertySource(properties = "app.account.password.lock-seconds=2")
class AccountPasswordIntegrationTest extends AccountIntegrationTestSupport {

    @TestConfiguration
    static class SmsConfig {
        @Bean
        @org.springframework.context.annotation.Primary
        TestSmsSender testSmsSender() {
            return new TestSmsSender();
        }
    }

    @Test
    void changesPasswordAndRevokesOldSessions() throws Exception {
        Session session = registerAndLogin();
        String csrf = accountCsrf(session);

        Resp changed = send("POST", "/api/v1/account/password/change", """
                {"currentPassword":"%s","newPassword":"NewStrongPass_456","confirmPassword":"NewStrongPass_456"}
                """.formatted(PASSWORD), bearer(session, csrf));
        assertThat(changed.status()).isEqualTo(200);
        assertThat(changed.json(objectMapper).at("/data/sessionsRevoked").asBoolean()).isTrue();

        Resp oldToken = send("GET", "/api/v1/account/profile", null, bearer(session, null));
        assertThat(oldToken.status()).isEqualTo(401);

        Resp oldLogin = send("POST", "/api/v1/auth/login", """
                {"email":"%s","password":"%s"}
                """.formatted(session.email(), PASSWORD), java.util.Map.of());
        assertThat(oldLogin.status()).isEqualTo(401);

        Resp newLogin = send("POST", "/api/v1/auth/login", """
                {"email":"%s","password":"NewStrongPass_456"}
                """.formatted(session.email()), java.util.Map.of());
        assertThat(newLogin.status()).isEqualTo(200);
    }

    @Test
    void rejectsWrongCurrentPassword() throws Exception {
        Session session = registerAndLogin();
        String csrf = accountCsrf(session);
        Resp resp = send("POST", "/api/v1/account/password/change", """
                {"currentPassword":"WrongPass_999","newPassword":"NewStrongPass_456","confirmPassword":"NewStrongPass_456"}
                """, bearer(session, csrf));
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.json(objectMapper).at("/error/key").asText()).isEqualTo("CURRENT_PASSWORD_INCORRECT");
    }

    @Test
    void rejectsInvalidNewPassword() throws Exception {
        Session session = registerAndLogin();
        String csrf = accountCsrf(session);
        Resp tooShort = send("POST", "/api/v1/account/password/change", """
                {"currentPassword":"%s","newPassword":"short","confirmPassword":"short"}
                """.formatted(PASSWORD), bearer(session, csrf));
        assertThat(tooShort.status()).isEqualTo(400);
        assertThat(tooShort.json(objectMapper).at("/error/key").asText()).isEqualTo("WEAK_PASSWORD");

        Resp mismatch = send("POST", "/api/v1/account/password/change", """
                {"currentPassword":"%s","newPassword":"NewStrongPass_456","confirmPassword":"OtherStrongPass_456"}
                """.formatted(PASSWORD), bearer(session, csrf));
        assertThat(mismatch.status()).isEqualTo(400);
        assertThat(mismatch.json(objectMapper).at("/error/key").asText()).isEqualTo("PASSWORD_CONFIRM_MISMATCH");
    }
}