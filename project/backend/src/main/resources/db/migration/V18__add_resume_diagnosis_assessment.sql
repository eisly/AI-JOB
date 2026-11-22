-- V18: 简历诊断新增可空评分结果列（resume-quality-v1 五维度评分）。
-- 旧诊断记录保持 NULL，继续按原有建议展示；新诊断在完成时与 items 同条 UPDATE 保存评分。
ALTER TABLE resume_diagnosis ADD COLUMN assessment_json JSON NULL;
