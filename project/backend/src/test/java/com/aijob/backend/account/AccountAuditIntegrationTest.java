package com.aijob.backend.account;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/career_business_test?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                "spring.flyway.enabled=true",
                "app.session.secure=false",
                "app.sms.enabled=false"
        })
class AccountAuditIntegrationTest extends AccountIntegrationTestSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void nicknameChangeIsAuditedWithoutSensitiveData() throws Exception {
        Session session = registerAndLogin();
        JsonNode loginUser = send("GET", "/api/v1/users/me", null, bearer(session, null))
                .json(objectMapper).at("/data");
        String userId = loginUser.at("/id").asText();
        String csrf = accountCsrf(session);

        Resp updated = send("PATCH", "/api/v1/account/nickname", """
                {"nickname":"审计昵称"}
                """, bearer(session, csrf));
        assertThat(updated.status()).isEqualTo(200);

        String details = jdbcTemplate.queryForObject("""
                SELECT details_json FROM audit_log
                WHERE actor_id = ? AND action = 'NICKNAME_UPDATE'
                ORDER BY created_at DESC LIMIT 1
                """, String.class, userId);
        assertThat(details).isNotNull().contains("SUCCESS");
        assertThat(details).doesNotContain(PASSWORD);
    }

    @Test
    void failedPasswordAttemptIsAuditedWithoutSecret() throws Exception {
        Session session = registerAndLogin();
        JsonNode me = send("GET", "/api/v1/users/me", null, bearer(session, null)).json(objectMapper).at("/data");
        String userId = me.at("/id").asText();
        String csrf = accountCsrf(session);

        Resp failed = send("POST", "/api/v1/account/password/change", """
                {"currentPassword":"WrongPass_999","newPassword":"NewStrongPass_456","confirmPassword":"NewStrongPass_456"}
                """, bearer(session, csrf));
        assertThat(failed.status()).isEqualTo(400);

        String details = jdbcTemplate.queryForObject("""
                SELECT details_json FROM audit_log
                WHERE actor_id = ? AND action = 'PASSWORD_CHANGE'
                ORDER BY created_at DESC LIMIT 1
                """, String.class, userId);
        assertThat(details).contains("FAILED").contains("CURRENT_PASSWORD_INCORRECT");
        assertThat(details).doesNotContain("WrongPass_999").doesNotContain("NewStrongPass_456");
        assertThat(new String(details.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).isNotBlank();
    }
}