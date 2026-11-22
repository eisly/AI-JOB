package com.aijob.backend.interview.voice;

/**
 * 转写失效原因：对外固定 4 个取值，禁止用数据库状态动态拼接 reason。
 */
public final class VoiceReasons {

    public static final String NOT_FOUND = "TRANSCRIPTION_NOT_FOUND";
    public static final String CONSUMED = "TRANSCRIPTION_CONSUMED";
    public static final String DISCARDED = "TRANSCRIPTION_DISCARDED";
    public static final String EXPIRED = "TRANSCRIPTION_EXPIRED";

    private VoiceReasons() {
    }

    /** PENDING/FAILED/EMPTY/DISCARDED 对提交方都表现为“不可用”，统一映射为 DISCARDED。 */
    public static String forStatus(String status) {
        if ("EXPIRED".equals(status)) return EXPIRED;
        return DISCARDED;
    }
}