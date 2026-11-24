package com.aijob.backend.account;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/career_business_test?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                "spring.flyway.enabled=true",
                "app.session.secure=false",
                "app.sms.enabled=false"
        })
class AccountCsrfIntegrationTest extends AccountIntegrationTestSupport {

    @Test
    void writeWithoutTokenIsRejected() throws Exception {
        Session session = registerAndLogin();
        Resp resp = send("PATCH", "/api/v1/account/nickname", """
                {"nickname":"无令牌"}
                """, bearer(session, null));
        assertThat(resp.status()).isEqualTo(403);
        assertThat(resp.json(objectMapper).at("/error/key").asText()).isEqualTo("CSRF_INVALID");
    }

    @Test
    void writeWithWrongTokenIsRejected() throws Exception {
        Session session = registerAndLogin();
        Resp resp = send("PATCH", "/api/v1/account/nickname", """
                {"nickname":"错误令牌"}
                """, bearer(session, "wrong-token"));
        assertThat(resp.status()).isEqualTo(403);
        assertThat(resp.json(objectMapper).at("/error/key").asText()).isEqualTo("CSRF_INVALID");
    }

    @Test
    void writeWithAccountTokenPasses() throws Exception {
        Session session = registerAndLogin();
        String csrf = accountCsrf(session);
        Resp resp = send("PATCH", "/api/v1/account/nickname", """
                {"nickname":"正确令牌"}
                """, bearer(session, csrf));
        assertThat(resp.status()).isEqualTo(200);
    }

    @Test
    void authCsrfCannotBeUsedForAccountWrites() throws Exception {
        Session session = registerAndLogin();
        Resp authCsrf = send("GET", "/api/v1/auth/csrf", null,
                bearer(session, null));
        assertThat(authCsrf.status()).isEqualTo(200);
        String token = authCsrf.json(objectMapper).at("/data/csrfToken").asText();
        Resp resp = send("PATCH", "/api/v1/account/nickname", """
                {"nickname":"跨域令牌"}
                """, bearer(session, token));
        assertThat(resp.status()).isEqualTo(403);
    }
}