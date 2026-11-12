-- 专项训练：支持基于已解析简历直接生成计划，并保留历史版本与训练依据。
-- MATCH 来源沿用 match_item_id；RESUME 来源使用 resume_version_id，两者互斥。

ALTER TABLE `learning_plan` DROP FOREIGN KEY `fk_learning_plan_30`;

ALTER TABLE `learning_plan`
  MODIFY COLUMN `match_item_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN `source` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'MATCH' AFTER `match_item_id`,
  ADD COLUMN `resume_version_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER `source`,
  ADD COLUMN `target_direction` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER `resume_version_id`,
  ADD COLUMN `supersedes_plan_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER `target_direction`,
  ADD COLUMN `generation_no` INT UNSIGNED NOT NULL DEFAULT 1 AFTER `supersedes_plan_id`,
  ADD COLUMN `basis_json` JSON NULL AFTER `generation_no`,
  ADD KEY `idx_learning_plan_user_source_created` (`user_id`, `source`, `created_at`, `id`),
  ADD CONSTRAINT `ck_learning_plan_source` CHECK (`source` IN ('MATCH', 'RESUME'));

ALTER TABLE `learning_plan`
  ADD CONSTRAINT `fk_learning_plan_30` FOREIGN KEY (`match_item_id`) REFERENCES `job_match_item` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT `fk_learning_plan_resume_version` FOREIGN KEY (`resume_version_id`) REFERENCES `resume_version` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `learning_plan_item`
  ADD COLUMN `category` VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'BASIC' AFTER `kind`,
  ADD COLUMN `objective` VARCHAR(500) NULL AFTER `category`,
  ADD COLUMN `reason` VARCHAR(500) NULL AFTER `objective`,
  ADD COLUMN `method` VARCHAR(500) NULL AFTER `reason`,
  ADD COLUMN `completion_criteria` VARCHAR(500) NULL AFTER `method`,
  ADD COLUMN `evidence_status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ASSESSED'
    AFTER `completion_criteria`,
  ADD COLUMN `evidence_quote` VARCHAR(500) NULL AFTER `evidence_status`,
  ADD COLUMN `resource_links_json` JSON NULL AFTER `evidence_quote`,
  ADD CONSTRAINT `ck_learning_plan_item_category` CHECK (`category` IN ('BASIC', 'RESUME_PROJECT', 'GITHUB')),
  ADD CONSTRAINT `ck_learning_plan_item_evidence` CHECK (`evidence_status` IN ('ASSESSED', 'PENDING'));