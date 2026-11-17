package com.aijob.backend.interview.voice;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 临时转写清理：未消费且过期 24 小时以上删除；已消费记录 7 天后清空文本、30 天后删除。
 * 清理失败只告警，不阻断业务；下一轮重试。
 */
@Component
public class VoiceTranscriptionSweeper {

    private static final Logger log = LoggerFactory.getLogger(VoiceTranscriptionSweeper.class);
    private static final int BATCH = 500;

    private final VoiceMapper voice;

    public VoiceTranscriptionSweeper(VoiceMapper voice) {
        this.voice = voice;
    }

    @Scheduled(fixedDelayString = "${app.voice.sweep-interval-ms:600000}", initialDelayString = "${app.voice.sweep-initial-delay-ms:60000}")
    public void sweep() {
        try {
            voice.expireStale();
            deleteAll(voice.staleUnconsumedIds(BATCH));
            clearAndDeleteConsumed();
        } catch (RuntimeException ex) {
            log.warn("voice sweep failed error=SWEEP_FAILED type={}", ex.getClass().getSimpleName());
        }
    }

    private void deleteAll(List<String> ids) {
        for (String id : ids) {
            voice.deleteById(id);
        }
    }

    private void clearAndDeleteConsumed() {
        for (String id : voice.consumedTextIds(BATCH)) {
            voice.clearText(id);
        }
        deleteAll(voice.expiredConsumedIds(BATCH));
    }
}