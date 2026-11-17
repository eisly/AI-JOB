-- V14: 专项训练支持"一次预生成一组题目"（选择题 / 填空题 / 主观题混合），并按整组交卷评估。
-- 设计原则：复用既有 learning_session / learning_answer / user_mistake，保证历史训练记录、
-- 错题本、能力证据与训练进度链路完全兼容；SINGLE 类型会话保持原有行为不变。

-- ---------------------------------------------------------------------------
-- 1. question：支持客观题结构（选项、标准答案、空数、分值）
-- ---------------------------------------------------------------------------
-- 批量出题时同一次 AI 任务会落库多道题，generated_task_id 不能再唯一。
-- 注意：generated_task_id 上有外键 fk_question_27，必须先补普通索引，再删唯一索引。
ALTER TABLE `question` ADD KEY `idx_question_generated_task` (`generated_task_id`);
ALTER TABLE `question` DROP INDEX `uk_generated_task_id`;

ALTER TABLE `question`
  ADD COLUMN `options_json` JSON NULL AFTER `stem`,
  ADD COLUMN `answer_key_json` JSON NULL AFTER `options_json`,
  ADD COLUMN `blank_count` TINYINT UNSIGNED NOT NULL DEFAULT 0 AFTER `answer_key_json`,
  ADD COLUMN `score` SMALLINT UNSIGNED NOT NULL DEFAULT 10 AFTER `blank_count`;

ALTER TABLE `question` DROP CHECK `ck_question_1`;
ALTER TABLE `question`
  ADD CONSTRAINT `ck_question_1` CHECK (type IN ('TEXT_SCENARIO','TEXT_KNOWLEDGE','CHOICE','FILL','SUBJECTIVE')),
  ADD CONSTRAINT `ck_question_score` CHECK (score BETWEEN 1 AND 100),
  ADD CONSTRAINT `ck_question_blank` CHECK (blank_count BETWEEN 0 AND 10);

-- ---------------------------------------------------------------------------
-- 2. learning_session：支持 GROUP 类型（一组题目）与整组评估结果
-- ---------------------------------------------------------------------------
ALTER TABLE `learning_session`
  ADD COLUMN `session_kind` VARCHAR(16) NOT NULL DEFAULT 'SINGLE' AFTER `plan_item_id`,
  ADD COLUMN `config_json` JSON NULL AFTER `session_kind`,
  ADD COLUMN `total_questions` INT UNSIGNED NOT NULL DEFAULT 1 AFTER `config_json`,
  ADD COLUMN `submitted_at` DATETIME(3) NULL AFTER `last_answer_id`,
  ADD COLUMN `duration_seconds` INT UNSIGNED NULL AFTER `submitted_at`,
  ADD COLUMN `result_json` JSON NULL AFTER `duration_seconds`,
  ADD COLUMN `evaluation_task_id` CHAR(36) NULL AFTER `result_json`;

ALTER TABLE `learning_session` DROP CHECK `ck_learning_session_1`;
ALTER TABLE `learning_session`
  ADD CONSTRAINT `ck_learning_session_1` CHECK (status IN ('PREPARING','ACTIVE','EVALUATING','COMPLETED','FAILED','SUBMITTED')),
  ADD CONSTRAINT `ck_learning_session_kind` CHECK (session_kind IN ('SINGLE','GROUP')),
  ADD CONSTRAINT `ck_learning_session_total` CHECK (total_questions BETWEEN 1 AND 20);

-- 会话与题目列表（GROUP 会话按 seq 保存整组题目）
CREATE TABLE `learning_session_question` (
  `id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `session_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `question_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `question_version` INT UNSIGNED NOT NULL DEFAULT 1,
  `seq` INT UNSIGNED NOT NULL,
  `score` SMALLINT UNSIGNED NOT NULL DEFAULT 10,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_question_seq` (`session_id`,`seq`),
  UNIQUE KEY `uk_session_question_id` (`session_id`,`question_id`),
  KEY `idx_lsq_question_id` (`question_id`),
  CONSTRAINT `ck_learning_session_question_score` CHECK (`score` BETWEEN 1 AND 100),
  CONSTRAINT `fk_lsq_session` FOREIGN KEY (`session_id`) REFERENCES `learning_session` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_lsq_question` FOREIGN KEY (`question_id`) REFERENCES `question` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 3. learning_answer：一个会话可保存多题答案
-- ---------------------------------------------------------------------------
-- session_id 上有外键，需先补普通索引再删唯一索引
ALTER TABLE `learning_answer` ADD KEY `idx_learning_answer_session` (`session_id`);
ALTER TABLE `learning_answer` DROP INDEX `uk_session_id`;

ALTER TABLE `learning_answer`
  ADD COLUMN `question_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER `session_id`,
  ADD COLUMN `question_version` INT UNSIGNED NULL AFTER `question_id`,
  ADD COLUMN `seq` INT UNSIGNED NULL AFTER `question_version`,
  ADD COLUMN `answer_json` JSON NULL AFTER `content`,
  ADD COLUMN `objective_correct` TINYINT(1) NULL AFTER `answer_json`,
  ADD COLUMN `score` DECIMAL(5,1) NULL AFTER `objective_correct`;

-- 历史单题答案回填题目信息，保持旧数据可追溯
UPDATE `learning_answer` a
  JOIN `learning_session` s ON s.id = a.session_id
SET a.question_id = s.question_id,
    a.question_version = s.question_version,
    a.seq = 1
WHERE a.question_id IS NULL AND s.question_id IS NOT NULL;

ALTER TABLE `learning_answer`
  ADD UNIQUE KEY `uk_session_question` (`session_id`,`question_id`),
  ADD KEY `idx_session_seq` (`session_id`,`seq`),
  ADD CONSTRAINT `fk_learning_answer_question` FOREIGN KEY (`question_id`) REFERENCES `question` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT `ck_learning_answer_score` CHECK (`score` IS NULL OR `score` BETWEEN 0 AND 100);
