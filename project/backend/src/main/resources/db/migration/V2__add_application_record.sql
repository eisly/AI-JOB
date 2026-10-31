-- AI-JOB V2: application_record for the application-progress module (P1).
-- See docs/功能资料/数据说明.md (表清单外追加) and 11-投递进度/投递进度后端接口示例.md.
-- Snapshot columns are filled from user-submitted content at creation time and never
-- synchronized back from job/job_version afterwards.
-- job_id is intentionally NOT unique: the same job may appear in multiple records.
-- Foreign keys follow the V1 global sequence (last used: fk_audit_log_64).

CREATE TABLE `application_record` (
  `id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `user_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `job_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `company_name` VARCHAR(100) NOT NULL,
  `job_title` VARCHAR(100) NOT NULL,
  `city_code` VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `education` VARCHAR(50) NULL DEFAULT NULL,
  `salary_text` VARCHAR(100) NULL DEFAULT NULL,
  `description` VARCHAR(1000) NULL DEFAULT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'PLANNED',
  `planned_date` DATE NULL DEFAULT NULL,
  `applied_date` DATE NULL DEFAULT NULL,
  `interview_date` DATE NULL DEFAULT NULL,
  `closed_date` DATE NULL DEFAULT NULL,
  `note` VARCHAR(2000) NULL DEFAULT NULL,
  `version` INT UNSIGNED NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`),
  KEY `idx_user_id_status_created_at_id` (`user_id`, `status`, `created_at`, `id`),
  KEY `idx_user_id_created_at_id` (`user_id`, `created_at`, `id`),
  CONSTRAINT `ck_application_record_1` CHECK (`status` IN ('PLANNED','APPLIED','INTERVIEWING','CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE `application_record` ADD CONSTRAINT `fk_application_record_65` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `application_record` ADD CONSTRAINT `fk_application_record_66` FOREIGN KEY (`job_id`) REFERENCES `job` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;