-- V22: 语音转写识别尝试序号
-- 目的：重试会复用同一条转写记录，仅凭记录 ID 或 PENDING 无法区分新旧识别尝试。
-- 回写时必须携带创建时的 attempt，条件更新只允许当前尝试生效，防止旧请求覆盖新尝试。
-- 说明：V17 已上线，本迁移只做增量列，不修改历史迁移。
ALTER TABLE `interview_voice_transcription`
  ADD COLUMN `attempt` INT UNSIGNED NOT NULL DEFAULT 1 AFTER `status`,
  ADD CONSTRAINT `ck_voice_attempt` CHECK (`attempt` >= 1);
