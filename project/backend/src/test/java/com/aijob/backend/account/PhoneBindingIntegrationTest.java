package com.aijob.backend.account;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Map;

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
class PhoneBindingIntegrationTest extends AccountIntegrationTestSupport {

    @TestConfiguration
    static class SmsConfig {
        @Bean
        @org.springframework.context.annotation.Primary
        TestSmsSender testSmsSender() {
            return new TestSmsSender();
        }
    }

    @Test
    void sendsCodeWhenChannelEnabled() throws Exception {
        Session session = registerAndLogin();
        String csrf = accountCsrf(session);

        Resp resp = send("POST", "/api/v1/account/phone/code", """
                {"phone":"13812345678","purpose":"BIND"}
                """, bearer(session, csrf));

        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.json(objectMapper).at("/data/devCode").asText()).matches("\\d{6}");
        assertThat(resp.json(objectMapper).at("/data/retryAfterSeconds").asLong()).isEqualTo(60);
    }

    @Test
    void bindsAndUnbindsPhone() throws Exception {
        Session session = registerAndLogin();
        String csrf = accountCsrf(session);
        String phone = uniquePhone();

        String code = sendCode(session, csrf, phone, "BIND");
        Resp bound = send("POST", "/api/v1/account/phone/bind", """
                {"phone":"%s","code":"%s"}
                """.formatted(phone, code), bearer(session, csrf));
        assertThat(bound.status()).isEqualTo(200);
        assertThat(bound.json(objectMapper).at("/data/phoneBound").asBoolean()).isTrue();
        assertThat(bound.json(objectMapper).at("/data/phoneMasked").asText())
                .isEqualTo(phone.substring(0, 3) + "****" + phone.substring(7));

        Resp unbindWithoutPassword = send("POST", "/api/v1/account/phone/unbind", """
                {"currentPassword":"WrongPass_999"}
                """, bearer(session, csrf));
        assertThat(unbindWithoutPassword.status()).isEqualTo(400);
        assertThat(unbindWithoutPassword.json(objectMapper).at("/error/key").asText())
                .isEqualTo("CURRENT_PASSWORD_INCORRECT");

        Resp unbound = send("POST", "/api/v1/account/phone/unbind", """
                {"currentPassword":"%s"}
                """.formatted(PASSWORD), bearer(session, csrf));
        assertThat(unbound.status()).isEqualTo(200);
        assertThat(unbound.json(objectMapper).at("/data/phoneBound").asBoolean()).isFalse();
        assertThat(unbound.json(objectMapper).at("/data/phoneMasked").isNull()).isTrue();
    }

    @Test
    void rejectsPhoneBoundToAnotherAccount() throws Exception {
        Session first = registerAndLogin();
        Session second = registerAndLogin();
        String phone = uniquePhone();
        String firstCsrf = accountCsrf(first);
        String code = sendCode(first, firstCsrf, phone, "BIND");
        Resp bound = send("POST", "/api/v1/account/phone/bind", """
                {"phone":"%s","code":"%s"}
                """.formatted(phone, code), bearer(first, firstCsrf));
        assertThat(bound.status()).isEqualTo(200);

        String secondCsrf = accountCsrf(second);
        Resp duplicate = send("POST", "/api/v1/account/phone/code", """
                {"phone":"%s","purpose":"BIND"}
                """.formatted(phone), bearer(second, secondCsrf));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.json(objectMapper).at("/error/key").asText()).isEqualTo("PHONE_ALREADY_BOUND");
    }

    @Test
    void rebindRequiresCurrentPassword() throws Exception {
        Session session = registerAndLogin();
        String csrf = accountCsrf(session);
        String firstPhone = uniquePhone();
        String firstCode = sendCode(session, csrf, firstPhone, "BIND");
        send("POST", "/api/v1/account/phone/bind", """
                {"phone":"%s","code":"%s"}
                """.formatted(firstPhone, firstCode), bearer(session, csrf));

        String secondPhone = uniquePhone();
        String secondCode = sendCode(session, csrf, secondPhone, "REBIND");

        Resp wrongPassword = send("POST", "/api/v1/account/phone/rebind", """
                {"currentPassword":"WrongPass_999","phone":"%s","code":"%s"}
                """.formatted(secondPhone, secondCode), bearer(session, csrf));
        assertThat(wrongPassword.status()).isEqualTo(400);

        Resp rebound = send("POST", "/api/v1/account/phone/rebind", """
                {"currentPassword":"%s","phone":"%s","code":"%s"}
                """.formatted(PASSWORD, secondPhone, secondCode), bearer(session, csrf));
        assertThat(rebound.status()).isEqualTo(200);
    }

    private String sendCode(Session session, String csrf, String phone, String purpose) throws Exception {
        Resp resp = send("POST", "/api/v1/account/phone/code", """
                {"phone":"%s","purpose":"%s"}
                """.formatted(phone, purpose), bearer(session, csrf));
        assertThat(resp.status()).isEqualTo(200);
        return resp.json(objectMapper).at("/data/devCode").asText();
    }

    private static String uniquePhone() {
        return "139" + String.format("%08d", Math.abs(java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000_000)));
    }
}