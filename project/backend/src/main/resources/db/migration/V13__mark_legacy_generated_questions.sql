-- V13: 修正 V12 遗留的历史生成题分类缺口（验收 T01 失败项）。
-- 背景：V12 为 question 新增 generation_env 时将历史题默认标为 PROD，
-- 但历史 GENERATED 题的模型来源不可追溯（可能来自 fake 模型的模板题），
-- 真实模式不应将其作为候选。复验证实：省略新字段的历史模拟题默认 PROD 后被真实模式选中。
-- 处理：以 V12 安装时间为界，V12 之前已存在的 GENERATED+PROD 题统一标记为 LEGACY，
-- 从真实候选池排除（宁缺毋滥：历史真实生成题一并排除，新写入的题已有明确来源标记不受影响）。
-- 既有会话对 LEGACY 题的引用不受影响，仅不再进入新候选。

UPDATE question q
SET q.generation_env = 'LEGACY'
WHERE q.source_type = 'GENERATED'
  AND q.generation_env = 'PROD'
  AND q.created_at < (
      SELECT MAX(installed_on) FROM flyway_schema_history WHERE version = '12' AND success = 1
  );
