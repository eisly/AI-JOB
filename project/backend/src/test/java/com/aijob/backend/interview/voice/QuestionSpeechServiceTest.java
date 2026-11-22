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
    void cacheKeyIncludesVoiceModelFormatAndTextDigest() {
        String first = QuestionSpeechService.cacheKey("i", "m", "Cherry", "qwen3-tts-flash", "wav", "题目");
        String second = QuestionSpeechService.cacheKey("i", "m", "Cherry", "qwen3-tts-flash", "mp3", "题目");
        String third = QuestionSpeechService.cacheKey("i", "m", "Cherry", "other-model", "wav", "题目");
        String fourth = QuestionSpeechService.cacheKey("i", "m", "Serena", "qwen3-tts-flash", "wav", "题目");
        assertTrue(!first.equals(second));
        assertTrue(!first.equals(third));
        assertTrue(!first.equals(fourth));
        assertTrue(first.contains("Cherry") && first.contains("qwen3-tts-flash") && first.contains("wav"));
    }

    @Test
    void limitsMatchDesign() {
        assertEquals(8 * 1024 * 1024, VoiceLimits.MAX_AUDIO_BYTES);
        assertEquals(180_000, VoiceLimits.MAX_DURATION_MS);
        assertEquals(1800, VoiceLimits.TRANSCRIPTION_TTL_SECONDS);
        assertEquals(500, VoiceLimits.MAX_TTS_CHARS);
    }
}