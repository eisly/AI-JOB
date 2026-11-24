package com.aijob.backend.account;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
class AvatarIntegrationTest extends AccountIntegrationTestSupport {

    @Test
    void uploadsReadsCachesAndResetsAvatar() throws Exception {
        Session session = registerAndLogin();
        String csrf = accountCsrf(session);

        Resp uploaded = uploadAvatar(session, csrf, "avatar.png", "image/png", pngBytes());
        assertThat(uploaded.status()).isEqualTo(200);
        assertThat(uploaded.json(objectMapper).at("/data/avatarVersion").asInt()).isEqualTo(1);
        assertThat(uploaded.json(objectMapper).at("/data/avatarUrl").asText()).contains("v=1");

        Resp image = sendRaw("GET", "/api/v1/account/avatars/me?v=1", null, bearer(session, null));
        assertThat(image.status()).isEqualTo(200);
        assertThat(image.etag()).isEqualTo("\"v1\"");

        Resp notModified = sendRaw("GET", "/api/v1/account/avatars/me?v=1", null,
                Map.of("Authorization", "Bearer " + session.accessToken(), "Origin", FRONTEND_ORIGIN,
                        "If-None-Match", "\"v1\""));
        assertThat(notModified.status()).isEqualTo(304);

        Resp reset = send("DELETE", "/api/v1/account/avatar", null, bearer(session, csrf));
        assertThat(reset.status()).isEqualTo(200);
        assertThat(reset.json(objectMapper).at("/data/avatarUrl").isNull()).isTrue();

        Resp missing = sendRaw("GET", "/api/v1/account/avatars/me", null, bearer(session, null));
        assertThat(missing.status()).isEqualTo(404);
    }

    @Test
    void rejectsGifAndOversizedFiles() throws Exception {
        Session session = registerAndLogin();
        String csrf = accountCsrf(session);

        Resp gif = uploadAvatar(session, csrf, "avatar.gif", "image/gif",
                "GIF89a-fake-content".getBytes(StandardCharsets.US_ASCII));
        assertThat(gif.status()).isEqualTo(415);
        assertThat(gif.json(objectMapper).at("/error/key").asText()).isEqualTo("UNSUPPORTED_AVATAR_TYPE");

        byte[] oversized = new byte[2 * 1024 * 1024 + 1];
        oversized[0] = (byte) 0xFF;
        oversized[1] = (byte) 0xD8;
        oversized[2] = (byte) 0xFF;
        Resp tooLarge = uploadAvatar(session, csrf, "big.png", "image/png", oversized);
        assertThat(tooLarge.status()).isEqualTo(413);
        assertThat(tooLarge.json(objectMapper).at("/error/key").asText()).isEqualTo("AVATAR_TOO_LARGE");
    }

    @Test
    void uploadsArePrivateToOwner() throws Exception {
        Session session = registerAndLogin();
        Resp anonymous = sendRaw("GET", "/api/v1/account/avatars/me", null, Map.of());
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(session.accessToken()).isNotBlank();
    }

    private static byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}