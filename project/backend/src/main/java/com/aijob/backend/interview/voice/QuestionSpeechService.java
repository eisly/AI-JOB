package com.aijob.backend.interview.voice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.aijob.backend.common.BusinessException;

/**
 * 题目播报：分句串行合成后合并为一段完整音频，结果只进内存 LRU 缓存。
 *
 * 供应商返回 WAV 时，每段都带独立 RIFF 头，必须去除文件头后合并 PCM 帧并重建头；
 * MP3 等连续码流则直接拼接。响应 MIME 以实际音频格式为准。
 */
@Service
public class QuestionSpeechService {

    private static final String PUNCTUATION = "。！？!?；;\n";

    private final SpeechAgentClient speech;
    private final SpeechAudioCache cache;
    private final VoiceRateLimiter rateLimiter;
    private final String defaultVoice;
    private final String model;
    private final String format;

    public QuestionSpeechService(SpeechAgentClient speech, SpeechAudioCache cache, VoiceRateLimiter rateLimiter,
                                 @Value("${app.voice.tts-voice:Cherry}") String defaultVoice,
                                 @Value("${app.voice.tts-model:qwen3-tts-flash}") String model,
                                 @Value("${app.voice.tts-format:wav}") String format) {
        this.speech = speech;
        this.cache = cache;
        this.rateLimiter = rateLimiter;
        this.defaultVoice = defaultVoice;
        this.model = model;
        this.format = format;
    }

    public AudioResult synthesize(String userId, String interviewId, String messageId, String text, String voice) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            throw BusinessException.validation("messageId", "题目文本为空，无法合成");
        }
        String selectedVoice = voice == null || voice.isBlank() ? defaultVoice : voice;
        String key = cacheKey(interviewId, messageId, selectedVoice, model, format, normalized);
        SpeechAudioCache.CachedAudio cached = cache.get(key);
        if (cached != null) {
            return new AudioResult(cached.data(), normalized.length(), selectedVoice, cached.mimeType());
        }
        rateLimiter.checkSynthesis(userId, normalized.length());
        List<AudioAssembler.Segment> parts = new ArrayList<>();
        for (String segment : segments(normalized)) {
            Map<String, Object> result = speech.synthesize(segment, selectedVoice, UUID.randomUUID().toString(), userId,
                    format);
            Object encoded = result.get("audioBase64");
            if (encoded == null) {
                throw BusinessException.dependencyUnavailable("题目语音合成返回为空");
            }
            byte[] audio = Base64.getDecoder().decode(String.valueOf(encoded));
            String mimeType = result.get("mimeType") == null ? null : String.valueOf(result.get("mimeType"));
            parts.add(new AudioAssembler.Segment(audio, mimeType));
        }
        AudioAssembler.Assembled assembled;
        try {
            assembled = AudioAssembler.assemble(parts);
        } catch (IllegalArgumentException ex) {
            throw BusinessException.dependencyUnavailable("题目语音分段格式异常");
        }
        cache.put(key, assembled.audio(), assembled.mimeType());
        return new AudioResult(assembled.audio(), normalized.length(), selectedVoice, assembled.mimeType());
    }

    static String cacheKey(String interviewId, String messageId, String voice, String model, String format,
                           String text) {
        return interviewId + ":" + messageId + ":" + voice + ":" + model + ":" + format + ":" + sha256(text);
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

    public record AudioResult(byte[] audio, int chars, String voice, String mimeType) {
    }
}