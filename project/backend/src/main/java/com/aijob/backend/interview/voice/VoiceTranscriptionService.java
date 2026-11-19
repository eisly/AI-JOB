package com.aijob.backend.interview.voice;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.interview.InterviewMapper;

/**
 * 逐题语音转写主流程。
 * 关键约束：转写只写 interview_voice_transcription，不写 interview_message、不递增 interview_session.version。
 */
@Service
public class VoiceTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(VoiceTranscriptionService.class);
    private static final Set<String> ALLOWED_MIME = Set.of("audio/webm", "audio/ogg", "audio/mp4");
    private static final int PENDING_STALE_SECONDS = 60;

    private final InterviewMapper interviews;
    private final VoiceMapper voice;
    private final SpeechAgentClient speech;
    private final VoiceCapabilityService capability;
    private final VoiceRateLimiter rateLimiter;
    private final TransactionTemplate tx;

    public VoiceTranscriptionService(InterviewMapper interviews, VoiceMapper voice, SpeechAgentClient speech,
                                     VoiceCapabilityService capability, VoiceRateLimiter rateLimiter,
                                     PlatformTransactionManager transactionManager) {
        this.interviews = interviews;
        this.voice = voice;
        this.speech = speech;
        this.capability = capability;
        this.rateLimiter = rateLimiter;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public Map<String, Object> transcribe(String userId, String interviewId, MultipartFile audio,
                                          String questionMessageId, int expectedVersion, int durationMs,
                                          String declaredMime) {
        validate(audio, durationMs, declaredMime);
        capability.requireConsent(userId);
        rateLimiter.checkTranscription(userId);
        String mime = audio.getContentType() == null ? declaredMime : audio.getContentType();
        mime = mime.toLowerCase().split(";")[0].trim();
        byte[] data = bytes(audio);
        String sha = sha256(data);
        Map<String, Object> existing = voice.findBySha(interviewId, questionMessageId, sha);
        if (existing != null) {
            expireIfStale(existing);
            String status = String.valueOf(existing.get("status"));
            if ("SUCCEEDED".equals(status)) {
                return vo(existing, true);
            }
            if ("PENDING".equals(status) && fresh(existing)) {
                throw BusinessException.taskInProgress(String.valueOf(existing.get("id")));
            }
        }
        String id = UUID.randomUUID().toString();
        final String pendingId = id;
        final String audioMime = mime;
        Map<String, Object> session = tx.execute(status -> {
            Map<String, Object> row = interviews.lockSession(interviewId, userId);
            if (row == null) throw BusinessException.notFound();
            int actual = ((Number) row.get("version")).intValue();
            if (actual != expectedVersion) throw BusinessException.versionConflict(actual);
            if (!"WAITING_ANSWER".equals(String.valueOf(row.get("status")))) {
                throw BusinessException.conflict("当前面试状态不能转写", "INVALID_STATE");
            }
            Map<String, Object> latest = interviews.latestQuestion(interviewId);
            if (latest == null || !questionMessageId.equals(String.valueOf(latest.get("id")))) {
                throw BusinessException.stale("只能转写当前问题", Map.of("reason", "QUESTION_CHANGED"));
            }
            Map<String, Object> duplicate = voice.findBySha(interviewId, questionMessageId, sha);
            if (duplicate == null) {
                voice.insertPending(pendingId, userId, interviewId, questionMessageId, expectedVersion, audioMime,
                        data.length, durationMs, sha, VoiceLimits.TRANSCRIPTION_TTL_SECONDS);
            }
            return row;
        });
        Map<String, Object> row = voice.findById(id, userId, interviewId);
        if (row == null) {
            Map<String, Object> duplicate = voice.findBySha(interviewId, questionMessageId, sha);
            if (duplicate == null) throw BusinessException.dependencyUnavailable("转写记录创建失败");
            row = duplicate;
            id = String.valueOf(row.get("id"));
        }
        Map<String, Object> result;
        try {
            result = speech.transcribe(data, mime, durationMs, auditRequestId(id), userId);
        } catch (RuntimeException ex) {
            voice.markFailed(id, errorKey(ex));
            throw ex;
        }
        Object text = result.get("text");
        String provider = String.valueOf(result.getOrDefault("provider", "unknown"));
        if (text == null || String.valueOf(text).isBlank()) {
            voice.markEmpty(id, provider, VoiceLimits.TRANSCRIPTION_TTL_SECONDS);
        } else {
            voice.markSucceeded(id, String.valueOf(text), provider, VoiceLimits.TRANSCRIPTION_TTL_SECONDS);
        }
        log.info("voice transcription interview={} question={} sha8={} bytes={} duration_ms={} outcome={}",
                interviewId, questionMessageId, sha.substring(0, 8), data.length, durationMs,
                text == null || String.valueOf(text).isBlank() ? "EMPTY" : "OK");
        return vo(voice.findById(id, userId, interviewId), false);
    }

    public Map<String, Object> latest(String userId, String interviewId, String questionMessageId) {
        Map<String, Object> session = interviews.session(interviewId, userId);
        if (session == null) throw BusinessException.notFound();
        Map<String, Object> row = voice.findLatestByQuestion(interviewId, questionMessageId);
        if (row == null) return null;
        expireIfStale(row);
        String status = String.valueOf(row.get("status"));
        if (Set.of("DISCARDED", "EXPIRED").contains(status)) return null;
        return vo(row, false);
    }

    public boolean discard(String userId, String interviewId, String id) {
        Map<String, Object> row = voice.findById(id, userId, interviewId);
        if (row == null) throw BusinessException.notFound();
        if (row.get("consumed_at") != null) {
            throw BusinessException.conflict("该转写已用于提交，不能放弃", "INVALID_STATE");
        }
        voice.markDiscarded(id);
        return true;
    }

    public Map<String, Object> usable(String userId, String interviewId, String id, String questionMessageId) {
        Map<String, Object> row = voice.findUsable(id, userId, interviewId, questionMessageId);
        if (row == null || row.get("consumed_at") != null) return null;
        expireIfStale(row);
        return "SUCCEEDED".equals(String.valueOf(row.get("status"))) ? row : null;
    }

    private void validate(MultipartFile audio, int durationMs, String declaredMime) {
        if (audio == null || audio.isEmpty()) {
            throw BusinessException.validation("audio", "录音不能为空");
        }
        if (audio.getSize() > VoiceLimits.MAX_AUDIO_BYTES) {
            throw BusinessException.payloadTooLarge();
        }
        String mime = audio.getContentType() == null ? declaredMime : audio.getContentType();
        mime = mime == null ? "" : mime.toLowerCase().split(";")[0].trim();
        if (!ALLOWED_MIME.contains(mime)) {
            throw BusinessException.unsupportedFileType();
        }
        if (durationMs < VoiceLimits.MIN_DURATION_MS || durationMs > VoiceLimits.MAX_DURATION_MS) {
            throw BusinessException.validation("durationMs", "录音时长必须在 2 秒到 180 秒之间");
        }
        byte[] head;
        try {
            head = audio.getInputStream().readNBytes(12);
        } catch (Exception ex) {
            throw BusinessException.unsupportedFileType();
        }
        if (!magicMatches(head)) {
            throw BusinessException.unsupportedFileType();
        }
    }

    private static boolean magicMatches(byte[] head) {
        if (head.length >= 4 && (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45
                && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3) return true;
        if (head.length >= 4 && head[0] == 'O' && head[1] == 'g' && head[2] == 'g' && head[3] == 'S') return true;
        return head.length >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
    }

    private Map<String, Object> vo(Map<String, Object> row, boolean reused) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("status", row.get("status"));
        result.put("text", row.get("text"));
        result.put("questionMessageId", row.get("question_message_id"));
        result.put("interviewVersion", row.get("interview_version"));
        result.put("durationMs", row.get("duration_ms"));
        result.put("expiresAt", iso(row.get("expires_at")));
        result.put("reused", reused);
        return result;
    }

    private void expireIfStale(Map<String, Object> row) {
        if (row.get("expires_at") == null) return;
        voice.expireIfStale(String.valueOf(row.get("id")));
        Map<String, Object> refreshed = voice.findBySha(String.valueOf(row.get("interview_id")),
                String.valueOf(row.get("question_message_id")), String.valueOf(row.get("audio_sha256")));
        if (refreshed != null) row.putAll(refreshed);
    }

    private static boolean fresh(Map<String, Object> row) {
        Object updated = row.get("updated_at");
        if (!(updated instanceof LocalDateTime time)) return true;
        return time.isAfter(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(PENDING_STALE_SECONDS));
    }

    private static String iso(Object value) {
        if (value instanceof LocalDateTime time) return time.toInstant(ZoneOffset.UTC).toString();
        if (value instanceof java.sql.Timestamp time) return time.toInstant().toString();
        return value == null ? null : String.valueOf(value);
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (Exception ex) {
            throw BusinessException.unsupportedFileType();
        }
    }

    private static String sha256(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String auditRequestId(String transcriptionId) {
        return UUID.nameUUIDFromBytes(("voice:" + transcriptionId).getBytes()).toString();
    }

    private static String errorKey(RuntimeException ex) {
        if (ex instanceof BusinessException business) return business.getErrorKey();
        return "SPEECH_UNAVAILABLE";
    }
}