package com.aijob.backend.interview.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.interview.InterviewMapper;

class VoiceConcurrencyGuardTest {

    @Test
    void staleVersionCannotUseCachedTranscription() {
        InterviewMapper interviews = mock(InterviewMapper.class);
        VoiceMapper voice = mock(VoiceMapper.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(interviews.lockSession("interview-1", "user-1"))
                .thenReturn(Map.of("version", 2, "status", "WAITING_ANSWER"));
        VoiceTranscriptionService service = new VoiceTranscriptionService(interviews, voice,
                mock(SpeechAgentClient.class), mock(VoiceCapabilityService.class), mock(VoiceRateLimiter.class), tx);
        var file = new MockMultipartFile("audio", "a.webm", "audio/webm", WEBM);
        BusinessException error = assertThrows(BusinessException.class, () -> service.transcribe(
                "user-1", "interview-1", file, "question-1", 1, 9000, "audio/webm"));
        assertEquals("VERSION_CONFLICT", error.getErrorKey());
        org.mockito.Mockito.verify(voice, org.mockito.Mockito.never()).findBySha(any(), any(), any());
    }

    private static final byte[] WEBM = new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 1, 2, 3, 4};

    @Test
    void rejectsDifferentAudioWhileQuestionHasFreshPending() {
        InterviewMapper interviews = mock(InterviewMapper.class);
        VoiceMapper voice = mock(VoiceMapper.class);
        SpeechAgentClient speech = mock(SpeechAgentClient.class);
        VoiceCapabilityService capability = mock(VoiceCapabilityService.class);
        VoiceRateLimiter rateLimiter = mock(VoiceRateLimiter.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        when(interviews.lockSession("interview-1", "user-1"))
                .thenReturn(Map.of("version", 1, "status", "WAITING_ANSWER"));
        when(interviews.latestQuestion("interview-1")).thenReturn(Map.of("id", "question-1"));
        when(voice.findPendingByQuestion("interview-1", "question-1")).thenReturn(Map.of(
                "id", "pending-1",
                "audio_sha256", "different-sha",
                "updated_at", LocalDateTime.now(ZoneOffset.UTC)));
        when(voice.findBySha(any(), any(), any())).thenReturn(null);

        VoiceTranscriptionService service = new VoiceTranscriptionService(
                interviews, voice, speech, capability, rateLimiter, tx);
        MockMultipartFile audio = new MockMultipartFile("audio", "answer.webm", "audio/webm", WEBM);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.transcribe("user-1", "interview-1", audio, "question-1", 1, 9000, "audio/webm"));
        assertEquals(409, error.getHttpStatus());
        assertEquals(40902, error.getCode());
        assertEquals("TASK_IN_PROGRESS", error.getErrorKey());
    }

    @Test
    void rejectsMimeThatDoesNotMatchContainer() {
        VoiceTranscriptionService service = new VoiceTranscriptionService(
                mock(InterviewMapper.class),
                mock(VoiceMapper.class),
                mock(SpeechAgentClient.class),
                mock(VoiceCapabilityService.class),
                mock(VoiceRateLimiter.class),
                mock(PlatformTransactionManager.class));
        byte[] mp4 = new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '};
        MockMultipartFile audio = new MockMultipartFile("audio", "answer.webm", "audio/webm", mp4);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.transcribe("user-1", "interview-1", audio, "question-1", 1, 9000, "audio/webm"));
        assertEquals(415, error.getHttpStatus());
        assertEquals("UNSUPPORTED_FILE_TYPE", error.getErrorKey());
    }
}
