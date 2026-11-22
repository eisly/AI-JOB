package com.aijob.backend.interview.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class QuestionSpeechServiceTest {

    @Test
    void keepsShortSentenceAsOneSegment() {
        List<String> segments = QuestionSpeechService.segments("请介绍你的项目。");
        assertEquals(List.of("请介绍你的项目。"), segments);
    }

    @Test
    void splitsByPunctuationAndKeepsOrder() {
        List<String> segments = QuestionSpeechService.segments("第一句。第二句！第三句？");
        assertEquals(List.of("第一句。", "第二句！", "第三句？"), segments);
    }

    @Test
    void hardSplitsOverlongSegment() {
        String text = "啊".repeat(250);
        List<String> segments = QuestionSpeechService.segments(text);
        assertEquals(3, segments.size());
        assertTrue(segments.stream().allMatch(item -> item.length() <= VoiceLimits.TTS_SEGMENT_CHARS));
        assertEquals(text, String.join("", segments));
    }

    @Test
    void emptyTextFallsBackToOriginal() {
        assertEquals(List.of("题目"), QuestionSpeechService.segments("题目"));
    }

    @Test
    void limitsMatchDesign() {
        assertEquals(8 * 1024 * 1024, VoiceLimits.MAX_AUDIO_BYTES);
        assertEquals(180_000, VoiceLimits.MAX_DURATION_MS);
        assertEquals(1800, VoiceLimits.TRANSCRIPTION_TTL_SECONDS);
        assertEquals(500, VoiceLimits.MAX_TTS_CHARS);
    }
}