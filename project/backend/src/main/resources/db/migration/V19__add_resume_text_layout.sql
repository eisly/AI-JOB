-- V19: 简历文本按页面位置恢复阅读顺序后的布局版本，以及诊断使用的授权文本快照。
-- 旧版本 text_layout_version 保持 NULL，表示按历史整篇提取规则生成；
-- 旧诊断 read_order_verified 默认 0，报告继续可读并明确标注阅读顺序未复核。
ALTER TABLE resume_version ADD COLUMN text_layout_version VARCHAR(32) NULL;

ALTER TABLE resume_diagnosis ADD COLUMN source_text MEDIUMTEXT NULL;
ALTER TABLE resume_diagnosis ADD COLUMN text_layout_version VARCHAR(32) NULL;
ALTER TABLE resume_diagnosis ADD COLUMN read_order_verified TINYINT(1) NOT NULL DEFAULT 0;