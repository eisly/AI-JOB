-- V17: 语音模拟面试（首版逐题整段录音 + 同步转写 + 服务端 TTS）
-- 设计基线：.codeartsdoer/specs/voice_interview/design.md 2.3.2
-- 原则：不改既有会话/消息语义；转写为临时数据，音频不落库；历史数据一律按文字处理。

-- ---------------------------------------------------------------------------
-- 1) 会话模式：历史数据默认 TEXT
-- ---------------------------------------------------------------------------
ALTER TABLE `interview_session`
  ADD COLUMN `mode` VARCHAR(8) NOT NULL DEFAULT 'TEXT' AFTER `status`,
  ADD CONSTRAINT `ck_interview_session_mode` CHECK (`mode` IN ('TEXT','VOICE'));

-- ---------------------------------------------------------------------------
-- 2) 回答来源：仅 USER 消息有来源，历史数据为 NULL（读取时按 TEXT 处理）
-- ---------------------------------------------------------------------------
ALTER TABLE `interview_message`
  ADD COLUMN `answer_source` VARCHAR(8) NULL DEFAULT NULL AFTER `role`,
  ADD CONSTRAINT `ck_interview_message_source`
    CHECK (`answer_source` IS NULL OR `answer_source` IN ('TEXT','VOICE')),
  ADD CONSTRAINT `ck_interview_message_source_role`
    CHECK (`role` = 'USER' OR `answer_source` IS NULL);

-- ---------------------------------------------------------------------------
-- 3) 转写记录（临时）：音频识别后即清理，只保留确认后的文本
-- ---------------------------------------------------------------------------
CREATE TABLE `interview_voice_transcription` (
  `id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `user_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `interview_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `question_message_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `interview_version` INT UNSIGNED NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `text` MEDIUMTEXT CHARACTER SET utf8mb4 NULL DEFAULT NULL,
  `mime_type` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `byte_size` INT UNSIGNED NOT NULL,
  `duration_ms` INT UNSIGNED NOT NULL,
  `audio_sha256` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `provider` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `error_key` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `expires_at` DATETIME(3) NOT NULL,
  `consumed_at` DATETIME(3) NULL DEFAULT NULL,
  `consumed_message_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_voice_audio_sha` (`interview_id`,`question_message_id`,`audio_sha256`),
  KEY `idx_voice_user_created` (`user_id`,`created_at`,`id`),
  KEY `idx_voice_question` (`interview_id`,`question_message_id`,`created_at`),
  KEY `idx_voice_expiry` (`expires_at`,`status`),
  CONSTRAINT `ck_voice_status`
    CHECK (`status` IN ('PENDING','SUCCEEDED','EMPTY','FAILED','DISCARDED','EXPIRED')),
  CONSTRAINT `ck_voice_bytes` CHECK (`byte_size` BETWEEN 1 AND 8388608),
  CONSTRAINT `ck_voice_duration` CHECK (`duration_ms` BETWEEN 1000 AND 180000),
  CONSTRAINT `ck_voice_text_len` CHECK (`text` IS NULL OR CHAR_LENGTH(`text`) <= 10000),
  CONSTRAINT `ck_voice_consumed`
    CHECK ((`consumed_at` IS NULL AND `consumed_message_id` IS NULL)
        OR (`consumed_at` IS NOT NULL AND `consumed_message_id` IS NOT NULL)),
  CONSTRAINT `fk_voice_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_voice_interview` FOREIGN KEY (`interview_id`) REFERENCES `interview_session` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_voice_question` FOREIGN KEY (`question_message_id`) REFERENCES `interview_message` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_voice_consumed_message` FOREIGN KEY (`consumed_message_id`) REFERENCES `interview_message` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 4) 语音隐私同意（按用户 + 政策版本唯一）
-- ---------------------------------------------------------------------------
CREATE TABLE `interview_voice_consent` (
  `id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `user_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `policy_version` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `agreed_at` DATETIME(3) NOT NULL,
  `revoked_at` DATETIME(3) NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_voice_consent_user_policy` (`user_id`,`policy_version`),
  KEY `idx_voice_consent_user` (`user_id`,`agreed_at`),
  CONSTRAINT `fk_voice_consent_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;