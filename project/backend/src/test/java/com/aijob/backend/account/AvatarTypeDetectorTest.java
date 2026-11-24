package com.aijob.backend.account;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AvatarTypeDetectorTest {

    private static byte[] jpeg() {
        byte[] bytes = new byte[16];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        bytes[3] = (byte) 0xE0;
        return bytes;
    }

    private static byte[] png() {
        byte[] bytes = new byte[16];
        bytes[0] = (byte) 0x89;
        bytes[1] = 'P';
        bytes[2] = 'N';
        bytes[3] = 'G';
        bytes[4] = 0x0D;
        bytes[5] = 0x0A;
        bytes[6] = 0x1A;
        bytes[7] = 0x0A;
        return bytes;
    }

    private static byte[] webp() {
        byte[] bytes = new byte[16];
        bytes[0] = 'R';
        bytes[1] = 'I';
        bytes[2] = 'F';
        bytes[3] = 'F';
        bytes[8] = 'W';
        bytes[9] = 'E';
        bytes[10] = 'B';
        bytes[11] = 'P';
        return bytes;
    }

    @Test
    void detectsSupportedTypes() {
        assertThat(AvatarTypeDetector.detect(jpeg())).isEqualTo("image/jpeg");
        assertThat(AvatarTypeDetector.detect(png())).isEqualTo("image/png");
        assertThat(AvatarTypeDetector.detect(webp())).isEqualTo("image/webp");
    }

    @Test
    void rejectsOtherTypes() {
        assertThat(AvatarTypeDetector.detect("GIF89a".getBytes(StandardCharsets.US_ASCII))).isNull();
        assertThat(AvatarTypeDetector.detect("%PDF-1.7".getBytes(StandardCharsets.US_ASCII))).isNull();
        assertThat(AvatarTypeDetector.detect("<svg xmlns=\"http://www.w3.org/2000/svg\">".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(AvatarTypeDetector.detect(new byte[0])).isNull();
    }

    @Test
    void webpContainerIsAcceptedWithoutDecode() {
        assertThat(AvatarTypeDetector.decodable(webp(), "image/webp")).isTrue();
    }

    @Test
    void corruptedPngIsNotDecodable() {
        assertThat(AvatarTypeDetector.decodable(png(), "image/png")).isFalse();
    }
}