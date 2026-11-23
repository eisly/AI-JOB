-- V23: 个人 JD 定向简历完整稿
-- 目的：记录生成模式、岗位快照与缺证要求，并在用户确认后保存可导出的完整简历正文。
-- 兼容：新增列全部可空，历史逐段优化记录保持原样；导出逻辑在缺少完整稿时回退旧的 accepted_sections_json。
ALTER TABLE `resume_optimization`
  ADD COLUMN `mode` VARCHAR(16) NULL AFTER `diagnosis_id`,
  ADD COLUMN `job_version` INT NULL AFTER `job_id`,
  ADD COLUMN `job_company_name` VARCHAR(100) NULL AFTER `job_version`,
  ADD COLUMN `job_title` VARCHAR(100) NULL AFTER `job_company_name`,
  ADD COLUMN `source_text` MEDIUMTEXT NULL AFTER `job_title`,
  ADD COLUMN `missing_requirements_json` JSON NULL AFTER `sections_json`,
  ADD COLUMN `notes_json` JSON NULL AFTER `missing_requirements_json`,
  ADD COLUMN `full_document_text` MEDIUMTEXT NULL AFTER `accepted_at`,
  ADD COLUMN `full_document_json` JSON NULL AFTER `full_document_text`;