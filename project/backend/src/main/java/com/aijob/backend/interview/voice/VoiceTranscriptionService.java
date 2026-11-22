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
        String mime = audio.getContentType() == null ? declaredMime : audio.getContentType();
        mime = mime == null ? "" : mime.toLowerCase().split(";")[0].trim();
        byte[] data = readBounded(audio);
        validate(data, mime, durationMs);
        capability.requireConsent(userId);
        rateLimiter.checkTranscription(userId);
        String sha = sha256(data);
        String createdId = UUID.randomUUID().toString();
        final String audioMime = mime;
        Map<String, Object> row = tx.execute(status -> {
            Map<String, Object> session = interviews.lockSession(interviewId, userId);
            if (session == null) throw BusinessException.notFound();
            int actual = ((Number) session.get("version")).intValue();
            if (actual != expectedVersion) throw BusinessException.versionConflict(actual);
            if (!"WAITING_ANSWER".equals(String.valueOf(session.get("status")))) {
                throw BusinessException.conflict("当前面试状态不能转写", "INVALID_STATE");
            }
            Map<String, Object> latest = interviews.latestQuestion(interviewId);
            if (latest == null || !questionMessageId.equals(String.valueOf(latest.get("id")))) {
                throw BusinessException.stale("只能转写当前问题", Map.of("reason", "QUESTION_CHANGED"));
            }
            Map<String, Object> duplicate = voice.findBySha(interviewId, questionMessageId, sha);
            if (duplicate != null) {
                expireIfStale(duplicate);
                if ("SUCCEEDED".equals(String.valueOf(duplicate.get("status")))) {
                    if (duplicate.get("consumed_at") != null) {
                        // 已消费结果不能再次复用，否则前端会拿到一个必然提交失败的候选文本。
                        throw BusinessException.stale("该转写已用于回答，请重新录音",
                                Map.of("reason", "TRANSCRIPTION_CONSUMED"));
                    }
                    duplicate.put("_cached", true);
                    return duplicate;
                }
            }
            Map<String, Object> pending = voice.findPendingByQuestion(interviewId, questionMessageId);
            if (pending != null && fresh(pending)) {
                throw BusinessException.taskInProgress(String.valueOf(pending.get("id")));
            }
            String recordId = duplicate == null ? createdId : String.valueOf(duplicate.get("id"));
            if (duplicate == null) {
                voice.insertPending(recordId, userId, interviewId, questionMessageId, expectedVersion, audioMime,
                        data.length, durationMs, sha, VoiceLimits.TRANSCRIPTION_TTL_SECONDS);
            } else {
                if (voice.resetPending(recordId, expectedVersion, VoiceLimits.TRANSCRIPTION_TTL_SECONDS) == 0) {
                    throw BusinessException.stale("转写已被消费", Map.of("reason", "TRANSCRIPTION_CONSUMED"));
                }
            }
            // 接管前收敛同题已超新鲜窗口的其它 PENDING，避免同题并存多条 PENDING。
            voice.discardStalePendingOfQuestion(interviewId, questionMessageId, recordId, PENDING_STALE_SECONDS);
            return voice.findById(recordId, userId, interviewId);
        });
        if (row == null) throw BusinessException.dependencyUnavailable("转写记录创建失败");
        if (Boolean.TRUE.equals(row.get("_cached"))) return vo(row, true);
        String id = String.valueOf(row.get("id"));
        int attempt = number(row.get("attempt"), 1);
        int version = number(row.get("interview_version"), expectedVersion);
        Map<String, Object> result;
        try {
            result = speech.transcribe(data, mime, durationMs, auditRequestId(id, attempt), userId);
        } catch (RuntimeException ex) {
            Guard guard = applyFailure(userId, interviewId, questionMessageId, version, id, attempt, errorKey(ex));
            if (guard != Guard.VALID) throw stale(guard);
            throw ex;
        }
        Object text = result.get("text");
        String provider = String.valueOf(result.getOrDefault("provider", "unknown"));
        boolean empty = text == null || String.valueOf(text).isBlank();
        Map<String, Object> saved = completeSuccess(userId, interviewId, questionMessageId, version, id, attempt,
                empty ? null : String.valueOf(text), provider, empty);
        log.info("voice transcription interview={} question={} sha8={} bytes={} duration_ms={} attempt={} outcome={}",
                interviewId, questionMessageId, sha.substring(0, 8), data.length, durationMs, attempt,
                empty ? "EMPTY" : "OK");
        return vo(saved, false);
    }

    /**
     * 供应商调用在事务外完成后，用短事务二次核对会话归属、会话状态、版本、当前题目和识别尝试。
     * 只有全部仍然有效才允许条件回写；否则只处理本尝试的 PENDING 记录，绝不改写会话或正式回答。
     */
    private Map<String, Object> completeSuccess(String userId, String interviewId, String questionMessageId,
                                                int version, String id, int attempt, String text, String provider,
                                                boolean empty) {
        Outcome outcome = tx.execute(status -> {
            Guard guard = evaluate(userId, interviewId, questionMessageId, version, id, attempt);
            if (guard != Guard.VALID) {
                settle(guard, id, attempt);
                return new Outcome(null, guard);
            }
            int changed = empty
                    ? voice.markEmptyIfPending(id, attempt, provider, VoiceLimits.TRANSCRIPTION_TTL_SECONDS)
                    : voice.markSucceededIfPending(id, attempt, text, provider,
                            VoiceLimits.TRANSCRIPTION_TTL_SECONDS);
            if (changed != 1) {
                // 极端竞态（校验后记录被放弃/过期/新尝试接管）：回读细分原因，且只清理本尝试。
                Guard after = inspectAfterZeroRows(userId, interviewId, id, attempt);
                settle(after, id, attempt);
                return new Outcome(null, after);
            }
            return new Outcome(voice.findById(id, userId, interviewId), Guard.VALID);
        });
        if (outcome == null || outcome.saved() == null) {
            throw stale(outcome == null ? Guard.STALE : outcome.guard());
        }
        return outcome.saved();
    }

    /** 失败结果同样只能在当前尝试仍有效时落 FAILED；失效尝试的失败不得覆盖任何状态。 */
    private Guard applyFailure(String userId, String interviewId, String questionMessageId, int version,
                               String id, int attempt, String errorKey) {
        Guard outcome = tx.execute(status -> {
            Guard guard = evaluate(userId, interviewId, questionMessageId, version, id, attempt);
            if (guard == Guard.VALID) {
                if (voice.markFailedIfPending(id, attempt, errorKey) == 1) return Guard.VALID;
                Guard after = inspectAfterZeroRows(userId, interviewId, id, attempt);
                settle(after, id, attempt);
                return after;
            }
            settle(guard, id, attempt);
            return guard;
        });
        return outcome == null ? Guard.STALE : outcome;
    }

    /**
     * 会话行锁内判定本次识别尝试是否仍然有效。
     * 与确认回答共用 interview_session 行锁，使"检查仍允许作答"与"结果回写"串行化。
     */
    private Guard evaluate(String userId, String interviewId, String questionMessageId, int version,
                           String id, int attempt) {
        Map<String, Object> session = interviews.lockSession(interviewId, userId);
        if (session == null) return Guard.GONE;
        if (!"WAITING_ANSWER".equals(String.valueOf(session.get("status")))) return Guard.STALE;
        if (number(session.get("version"), -1) != version) return Guard.STALE;
        Map<String, Object> latest = interviews.latestQuestion(interviewId);
        if (latest == null || !questionMessageId.equals(String.valueOf(latest.get("id")))) return Guard.STALE;
        Map<String, Object> record = voice.findById(id, userId, interviewId);
        if (record == null) return Guard.GONE;
        if (record.get("consumed_at") != null) return Guard.CONSUMED;
        String recordStatus = String.valueOf(record.get("status"));
        if ("EXPIRED".equals(recordStatus)) return Guard.EXPIRED;
        if (!"PENDING".equals(recordStatus)) return Guard.GONE;
        if (number(record.get("attempt"), 1) != attempt) return Guard.OLD_ATTEMPT;
        // 过期判定以数据库时间为唯一裁决：此处不再用 JVM 时钟，最终由条件更新 0 行回读确认。
        return Guard.VALID;
    }

    /**
     * 条件更新返回 0 行后回读记录细分原因。
     * 只有本尝试仍为 PENDING 时才允许由 settle 落失效状态；已消费、旧尝试、已终态一律零落盘。
     */
    private Guard inspectAfterZeroRows(String userId, String interviewId, String id, int attempt) {
        Map<String, Object> record = voice.findById(id, userId, interviewId);
        if (record == null) return Guard.GONE;
        if (record.get("consumed_at") != null) return Guard.CONSUMED;
        if (number(record.get("attempt"), 1) != attempt) return Guard.OLD_ATTEMPT;
        String status = String.valueOf(record.get("status"));
        if ("EXPIRED".equals(status)) return Guard.EXPIRED;
        if (!"PENDING".equals(status)) return Guard.GONE;
        voice.expireIfStale(id);
        Map<String, Object> refreshed = voice.findById(id, userId, interviewId);
        if (refreshed == null) return Guard.GONE;
        if ("EXPIRED".equals(String.valueOf(refreshed.get("status")))) return Guard.EXPIRED;
        return Guard.STALE;
    }

    /** 失效结果只允许把"本尝试的 PENDING"落为 DISCARDED/EXPIRED；已放弃、已消费、旧尝试一律不动。 */
    private void settle(Guard guard, String id, int attempt) {
        if (guard == Guard.STALE) {
            voice.markDiscardedIfPending(id, attempt);
        } else if (guard == Guard.EXPIRED) {
            voice.markExpiredIfPending(id, attempt);
        }
    }

    private static BusinessException stale(Guard guard) {
        if (guard == Guard.EXPIRED) {
            return BusinessException.stale("识别结果已过期，请重新录音",
                    Map.of("reason", "TRANSCRIPTION_EXPIRED"));
        }
        if (guard == Guard.CONSUMED) {
            return BusinessException.stale("该转写已用于回答，请重新录音或改用文字",
                    Map.of("reason", "TRANSCRIPTION_CONSUMED"));
        }
        return BusinessException.stale("题目已变化，本次识别未生效",
                Map.of("reason", "QUESTION_CHANGED"));
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
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

    private static void validate(byte[] data, String mime, int durationMs) {
        if (data.length == 0) {
            throw BusinessException.validation("audio", "录音不能为空");
        }
        if (data.length > VoiceLimits.MAX_AUDIO_BYTES) {
            throw BusinessException.payloadTooLarge();
        }
        if (durationMs < VoiceLimits.MIN_DURATION_MS || durationMs > VoiceLimits.MAX_DURATION_MS) {
            throw BusinessException.validation("durationMs", "录音时长必须在 2 秒到 180 秒之间");
        }
        if (!ALLOWED_MIME.contains(mime)) {
            throw BusinessException.unsupportedAudioType();
        }
        String container = detectContainer(data);
        if (container == null || !mimeFor(container).equals(mime)) {
            throw BusinessException.unsupportedAudioType();
        }
    }

    /** 有界读取：不信任 MultipartFile#getSize，最多读取上限 +1 字节用于判定超限。 */
    static byte[] readBounded(MultipartFile audio) {
        if (audio.getSize() > VoiceLimits.MAX_AUDIO_BYTES) {
            throw BusinessException.payloadTooLarge();
        }
        try (java.io.InputStream input = audio.getInputStream()) {
            byte[] data = input.readNBytes(VoiceLimits.MAX_AUDIO_BYTES + 1);
            if (data.length > VoiceLimits.MAX_AUDIO_BYTES) {
                throw BusinessException.payloadTooLarge();
            }
            return data;
        } catch (java.io.IOException ex) {
            throw BusinessException.unsupportedAudioType();
        }
    }

    static String detectContainer(byte[] data) {
        if (data.length >= 4 && (data[0] & 0xFF) == 0x1A && (data[1] & 0xFF) == 0x45
                && (data[2] & 0xFF) == 0xDF && (data[3] & 0xFF) == 0xA3) return "webm";
        if (data.length >= 4 && data[0] == 'O' && data[1] == 'g' && data[2] == 'g' && data[3] == 'S') return "ogg";
        if (data.length >= 8 && data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p') return "mp4";
        return null;
    }

    static String mimeFor(String container) {
        return switch (container) {
            case "webm" -> "audio/webm";
            case "ogg" -> "audio/ogg";
            case "mp4" -> "audio/mp4";
            default -> "";
        };
    }

    private Map<String, Object> vo(Map<String, Object> row, boolean reused) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("status", row.get("status"));
        result.put("text", row.get("text"));
        result.put("questionMessageId", row.get("question_message_id"));
        result.put("interviewVersion", row.get("interview_version"));
        result.put("durationMs", row.get("duration_ms"));
        result.put("attempt", row.get("attempt"));
        result.put("expiresAt", iso(row.get("expires_at")));
        result.put("reused", reused);
        return result;
    }

    /** 二次校验结论：VALID 才允许回写；STALE/EXPIRED 可在本尝试仍 PENDING 时落失效状态。 */
    enum Guard {
        VALID,
        STALE,
        EXPIRED,
        CONSUMED,
        OLD_ATTEMPT,
        GONE
    }

    private record Outcome(Map<String, Object> saved, Guard guard) {
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
        if (updated instanceof LocalDateTime time) {
            return time.isAfter(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(PENDING_STALE_SECONDS));
        }
        if (updated instanceof java.sql.Timestamp time) {
            return time.toInstant().isAfter(java.time.Instant.now().minusSeconds(PENDING_STALE_SECONDS));
        }
        return true;
    }

    private static String iso(Object value) {
        if (value instanceof LocalDateTime time) return time.toInstant(ZoneOffset.UTC).toString();
        if (value instanceof java.sql.Timestamp time) return time.toInstant().toString();
        return value == null ? null : String.valueOf(value);
    }


    private static String sha256(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String auditRequestId(String transcriptionId, int attempt) {
        return UUID.nameUUIDFromBytes(("voice:" + transcriptionId + ":" + attempt).getBytes()).toString();
    }

    private static String errorKey(RuntimeException ex) {
        if (ex instanceof BusinessException business) return business.getErrorKey();
        return "SPEECH_UNAVAILABLE";
    }
}
