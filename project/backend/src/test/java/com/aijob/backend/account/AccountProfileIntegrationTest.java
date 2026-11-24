package com.aijob.backend.account;

import com.aijob.backend.common.ApiErrorCode;
import com.aijob.backend.common.BusinessException;
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
class AccountProfileIntegrationTest extends AccountIntegrationTestSupport {

    @Test
    void readsAndUpdatesProfile() throws Exception {
        Session session = registerAndLogin();

        Resp profile = send("GET", "/api/v1/account/profile", null, bearer(session, null));
        assertThat(profile.status()).isEqualTo(200);
        assertThat(profile.json(objectMapper).at("/data/phoneBound").asBoolean()).isFalse();
        assertThat(profile.json(objectMapper).at("/data/avatarUrl").isNull()).isTrue();

        String csrf = accountCsrf(session);
        Resp updated = send("PATCH", "/api/v1/account/nickname", """
                {"nickname":"新昵称"}
                """, bearer(session, csrf));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.json(objectMapper).at("/data/nickname").asText()).isEqualTo("新昵称");
        assertThat(updated.json(objectMapper).at("/data/profileUpdatedAt").isNull()).isFalse();

        Resp me = send("GET", "/api/v1/users/me", null, bearer(session, null));
        assertThat(me.json(objectMapper).at("/data/nickname").asText()).isEqualTo("新昵称");
    }

    @Test
    void unchangedNicknameDoesNotRefreshTimestamp() throws Exception {
        Session session = registerAndLogin();
        String csrf = accountCsrf(session);
        Resp first = send("PATCH", "/api/v1/account/nickname", """
                {"nickname":"同名昵称"}
                """, bearer(session, csrf));
        String firstTimestamp = first.json(objectMapper).at("/data/profileUpdatedAt").asText();

        Resp second = send("PATCH", "/api/v1/account/nickname", """
                {"nickname":"  同名昵称  "}
                """, bearer(session, csrf));
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.json(objectMapper).at("/data/profileUpdatedAt").asText()).isEqualTo(firstTimestamp);
    }

    @Test
    void rejectsNicknameWithControlChars() throws Exception {
        Session session = registerAndLogin();
        String csrf = accountCsrf(session);
        Resp resp = send("PATCH", "/api/v1/account/nickname", """
                {"nickname":"昵称\\n换行"}
                """, bearer(session, csrf));
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.json(objectMapper).at("/error/key").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void capabilitiesReportSmsUnavailable() throws Exception {
        Session session = registerAndLogin();
        Resp resp = send("GET", "/api/v1/account/capabilities", null, bearer(session, null));
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.json(objectMapper).at("/data/smsBindingAvailable").asBoolean()).isFalse();
        assertThat(resp.json(objectMapper).at("/data/avatarMaxBytes").asLong()).isEqualTo(2097152);
    }

    @Test
    void requiresAuthentication() throws Exception {
        Resp resp = send("GET", "/api/v1/account/profile", null, Map.of());
        assertThat(resp.status()).isEqualTo(401);
        assertThat(resp.json(objectMapper).at("/error/key").asText())
                .isEqualTo(ApiErrorCode.UNAUTHENTICATED.getKey());
        assertThat(new BusinessException(ApiErrorCode.UNAUTHENTICATED).getHttpStatus()).isEqualTo(401);
    }
}