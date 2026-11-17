package com.aijob.backend.interview.voice;

/** 语音面试的单一常量事实源：与 Agent speech_*、前端 limits.ts 保持一致。 */
public final class VoiceLimits {

    public static final int MAX_AUDIO_BYTES = 8 * 1024 * 1024;
    public static final int MIN_DURATION_MS = 2000;
    public static final int MAX_DURATION_MS = 180_000;
    public static final int TRANSCRIPTION_TTL_SECONDS = 1800;
    public static final int MAX_TTS_CHARS = 500;
    public static final int TTS_SEGMENT_CHARS = 120;

    private VoiceLimits() {
    }
}