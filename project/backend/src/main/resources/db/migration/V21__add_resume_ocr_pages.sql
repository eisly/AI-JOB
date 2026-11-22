-- V21: 简历版本增加 OCR 页码记录。
-- 图片 PDF 经 RESUME_OCR 任务识别后，记录哪些页来自 OCR，
-- 供前端在引用位置旁标注「OCR 识别」。

ALTER TABLE resume_version ADD COLUMN ocr_pages_json VARCHAR(2000) NULL;
