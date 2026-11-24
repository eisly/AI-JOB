-- 账号管理：为 app_user 增加手机号、头像、资料保存时点与密码失败限流列。
-- 所有新增列均可空或带默认值，存量账号无需回填。
-- 注意：phone 的唯一性不区分 deleted_at（本期不提供账号注销入口，未来注销前须先解绑）。

ALTER TABLE `app_user`
  ADD COLUMN `phone` VARCHAR(11) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL AFTER `nickname`,
  ADD COLUMN `phone_bound_at` DATETIME(3) NULL DEFAULT NULL AFTER `phone`,
  ADD COLUMN `avatar_storage_key` VARCHAR(512) NULL DEFAULT NULL AFTER `phone_bound_at`,
  ADD COLUMN `avatar_mime_type` VARCHAR(100) NULL DEFAULT NULL AFTER `avatar_storage_key`,
  ADD COLUMN `avatar_size_bytes` BIGINT UNSIGNED NULL DEFAULT NULL AFTER `avatar_mime_type`,
  ADD COLUMN `avatar_version` INT UNSIGNED NOT NULL DEFAULT 0 AFTER `avatar_size_bytes`,
  ADD COLUMN `profile_updated_at` DATETIME(3) NULL DEFAULT NULL AFTER `avatar_version`,
  ADD COLUMN `password_failed_attempts` INT UNSIGNED NOT NULL DEFAULT 0 AFTER `profile_updated_at`,
  ADD COLUMN `password_locked_until` DATETIME(3) NULL DEFAULT NULL AFTER `password_failed_attempts`;

ALTER TABLE `app_user` ADD UNIQUE KEY `uk_app_user_phone` (`phone`);

-- 手机号格式的最终判定以服务层为准：MySQL 8 CHECK 对 REGEXP 类函数的支持依赖具体版本，
-- 为避免迁移失败，此处不添加 REGEXP CHECK；如需数据库兜底，可升级 MySQL 后另行评估。
ALTER TABLE `app_user`
  ADD CONSTRAINT `ck_app_user_avatar_mime`
  CHECK (`avatar_mime_type` IS NULL OR `avatar_mime_type` IN ('image/jpeg', 'image/png', 'image/webp'));

ALTER TABLE `app_user`
  ADD CONSTRAINT `ck_app_user_avatar_size`
  CHECK (`avatar_size_bytes` IS NULL OR `avatar_size_bytes` <= 2097152);