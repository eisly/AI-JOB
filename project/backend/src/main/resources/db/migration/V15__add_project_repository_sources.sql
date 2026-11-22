-- V15: 项目实战推荐改为真实开源仓库来源。
-- 旧推荐批次与旧计划保持可读：历史行 mode 自动落为 LEGACY_IDEA，来源字段保持 NULL；
-- 新批次由服务端写入 REPOSITORY，并保存各平台检索状态与仓库来源快照。

ALTER TABLE `project_recommendation`
  ADD COLUMN `mode` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'LEGACY_IDEA'
    AFTER `profile_version`,
  ADD COLUMN `source_runs_json` JSON NULL AFTER `items_json`,
  ADD CONSTRAINT `ck_project_recommendation_mode` CHECK (`mode` IN ('REPOSITORY', 'LEGACY_IDEA'));

ALTER TABLE `project_recommendation_item`
  ADD COLUMN `getting_started_json` JSON NULL AFTER `reason`,
  ADD COLUMN `primary_source_json` JSON NULL AFTER `getting_started_json`,
  ADD COLUMN `alternate_sources_json` JSON NULL AFTER `primary_source_json`;

ALTER TABLE `project`
  ADD COLUMN `primary_source_json` JSON NULL AFTER `summary`;