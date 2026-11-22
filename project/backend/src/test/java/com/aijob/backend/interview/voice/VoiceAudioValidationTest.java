package com.aijob.backend.interview.voice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.aijob.backend.common.BusinessException;

class VoiceAudioValidationTest {

    private static final byte[] WEBM = new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 1, 2, 3, 4};
    private static final byte[] OGG = new byte[] {'O', 'g', 'g', 'S', 1, 2, 3, 4};
    private static final byte[] MP4 = new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '};

    @Test
    void detectsSupportedContainers() {
        assertEquals("webm", VoiceTranscriptionService.detectContainer(WEBM));
        assertEquals("ogg", VoiceTranscriptionService.detectContainer(OGG));
        assertEquals("mp4", VoiceTranscriptionService.detectContainer(MP4));
        assertEquals("audio/webm", VoiceTranscriptionService.mimeFor("webm"));
        assertEquals("audio/ogg", VoiceTranscriptionService.mimeFor("ogg"));
        assertEquals("audio/mp4", VoiceTranscriptionService.mimeFor("mp4"));
    }

    @Test
    void rejectsUnknownContainer() {
        assertNull(VoiceTranscriptionService.detectContainer("plain text".getBytes()));
        assertEquals("", VoiceTranscriptionService.mimeFor("unknown"));
    }

    @Test
    void readsAudioWithinLimit() {
        MockMultipartFile file = new MockMultipartFile("audio", "answer.webm", "audio/webm", WEBM);
        assertArrayEquals(WEBM, VoiceTranscriptionService.readBounded(file));
    }

    @Test
    void rejectsOversizedAudioBeforeReading() {
        byte[] huge = new byte[VoiceLimits.MAX_AUDIO_BYTES + 1];
        MockMultipartFile file = new MockMultipartFile("audio", "answer.webm", "audio/webm", huge);
        BusinessException error = assertThrows(BusinessException.class,
                () -> VoiceTranscriptionService.readBounded(file));
        assertEquals(413, error.getHttpStatus());
        assertEquals("PAYLOAD_TOO_LARGE", error.getErrorKey());
    }
}