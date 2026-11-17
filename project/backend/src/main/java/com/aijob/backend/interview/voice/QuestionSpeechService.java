package com.aijob.backend.interview.voice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.aijob.backend.common.BusinessException;

/** 题目播报：分句串行合成后一次性返回完整 mp3，结果只进内存 LRU 缓存。 */
@Service
public class QuestionSpeechService {

    private static final String DEFAULT_VOICE = "longxiaochun";
    private static final String PUNCTUATION = "。！？!?；;\n";

    private final SpeechAgentClient speech;
    private final SpeechAudioCache cache;
    private final VoiceRateLimiter rateLimiter;

    public QuestionSpeechService(SpeechAgentClient speech, SpeechAudioCache cache, VoiceRateLimiter rateLimiter) {
        this.speech = speech;
        this.cache = cache;
        this.rateLimiter = rateLimiter;
    }

    public AudioResult synthesize(String userId, String interviewId, String messageId, String text, String voice) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            throw BusinessException.validation("messageId", "题目文本为空，无法合成");
        }
        String selectedVoice = voice == null || voice.isBlank() ? DEFAULT_VOICE : voice;
        String key = interviewId + ":" + messageId + ":" + selectedVoice + ":" + sha256(normalized);
        byte[] cached = cache.get(key);
        if (cached != null) {
            return new AudioResult(cached, normalized.length(), selectedVoice);
        }
        rateLimiter.checkSynthesis(userId, normalized.length());
        List<String> segments = segments(normalized);
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        for (String segment : segments) {
            Map<String, Object> result = speech.synthesize(segment, selectedVoice, UUID.randomUUID().toString(), userId);
            Object encoded = result.get("audioBase64");
            if (encoded == null) {
                throw BusinessException.dependencyUnavailable("题目语音合成返回为空");
            }
            byte[] audio = Base64.getDecoder().decode(String.valueOf(encoded));
            output.writeBytes(audio);
        }
        byte[] audio = output.toByteArray();
        cache.put(key, audio);
        return new AudioResult(audio, normalized.length(), selectedVoice);
    }

    /** 按标点切句，单段不超过 TTS_SEGMENT_CHARS，超长段按逗号/空格硬切。 */
    static List<String> segments(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (char ch : text.toCharArray()) {
            current.append(ch);
            if (PUNCTUATION.indexOf(ch) >= 0) {
                flush(result, current);
            }
        }
        flush(result, current);
        List<String> normalized = new ArrayList<>();
        for (String item : result) {
            if (item.length() <= VoiceLimits.TTS_SEGMENT_CHARS) {
                normalized.add(item);
            } else {
                for (int start = 0; start < item.length(); start += VoiceLimits.TTS_SEGMENT_CHARS) {
                    normalized.add(item.substring(start, Math.min(item.length(), start + VoiceLimits.TTS_SEGMENT_CHARS)));
                }
            }
        }
        return normalized.isEmpty() ? List.of(text) : normalized;
    }

    private static void flush(List<String> result, StringBuilder current) {
        String value = current.toString().trim();
        if (!value.isEmpty()) result.add(value);
        current.setLength(0);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record AudioResult(byte[] audio, int chars, String voice) {
    }
}