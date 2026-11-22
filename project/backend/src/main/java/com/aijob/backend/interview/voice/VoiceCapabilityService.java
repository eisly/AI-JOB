package com.aijob.backend.interview.voice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.interview.InterviewMapper;

/** 语音能力探测：前端据此决定是否展示语音入口与阈值，避免前后端各写一套常量。 */
@Service
public class VoiceCapabilityService {

    private static final String POLICY_VERSION = "voice-2026-11";

    private final InterviewMapper interviews;
    private final VoiceMapper voice;
    private final boolean asrEnabled;
    private final boolean ttsEnabled;
    private final boolean consentRequired;

    public VoiceCapabilityService(InterviewMapper interviews, VoiceMapper voice,
                                  @Value("${app.voice.asr-enabled:true}") boolean asrEnabled,
                                  @Value("${app.voice.tts-enabled:true}") boolean ttsEnabled,
                                  @Value("${app.voice.consent-required:true}") boolean consentRequired) {
        this.interviews = interviews;
        this.voice = voice;
        this.asrEnabled = asrEnabled;
        this.ttsEnabled = ttsEnabled;
        this.consentRequired = consentRequired;
    }

    public Map<String, Object> capability(String userId, String interviewId) {
        Map<String, Object> session = interviews.session(interviewId, userId);
        if (session == null) {
            throw BusinessException.notFound();
        }
        boolean consented = !consentRequired || consent(userId) != null
                && consent(userId).get("revoked_at") == null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("asrEnabled", asrEnabled);
        result.put("ttsEnabled", ttsEnabled);
        result.put("consentRequired", consentRequired);
        result.put("consentVersion", POLICY_VERSION);
        result.put("consented", consented);
        result.put("maxDurationSeconds", VoiceLimits.MAX_DURATION_MS / 1000);
        result.put("maxAudioBytes", VoiceLimits.MAX_AUDIO_BYTES);
        result.put("allowedMimeTypes", List.of("audio/webm", "audio/ogg", "audio/mp4"));
        result.put("transcriptionTtlSeconds", VoiceLimits.TRANSCRIPTION_TTL_SECONDS);
        return result;
    }

    public boolean consentRequired() {
        return consentRequired;
    }

    public String policyVersion() {
        return POLICY_VERSION;
    }

    public Map<String, Object> consent(String userId) {
        return voice.findConsent(userId, POLICY_VERSION);
    }

    public Map<String, Object> agree(String userId, String policyVersion, boolean agreed) {
        if (!POLICY_VERSION.equals(policyVersion)) {
            throw BusinessException.validation("policyVersion", "隐私政策版本已更新，请刷新后重试");
        }
        if (agreed) {
            voice.upsertConsent(java.util.UUID.randomUUID().toString(), userId, policyVersion);
        } else {
            voice.revokeConsent(userId, policyVersion);
        }
        return consentVo(userId);
    }

    public void requireConsent(String userId) {
        if (!consentRequired) {
            return;
        }
        Map<String, Object> row = voice.findConsent(userId, POLICY_VERSION);
        if (row == null) {
            throw BusinessException.conflict("请先阅读并同意语音隐私告知", "INVALID_STATE",
                    Map.of("reason", "CONSENT_REQUIRED"));
        }
        if (row.get("revoked_at") != null) {
            throw BusinessException.conflict("语音隐私同意已撤回，请重新确认", "INVALID_STATE",
                    Map.of("reason", "CONSENT_REVOKED"));
        }
    }

    private Map<String, Object> consentVo(String userId) {
        Map<String, Object> row = voice.findConsent(userId, POLICY_VERSION);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agreed", row != null && row.get("revoked_at") == null);
        result.put("policyVersion", POLICY_VERSION);
        result.put("agreedAt", row == null ? null : row.get("agreed_at"));
        return result;
    }
}