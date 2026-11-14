-- V12: 隔离 fake 模型生成的训练题，并区分错题本中"不会"与"答错"两类待复习记录。
-- 背景：fake 模型（本地无凭据/测试环境）生成的 GENERATED 题曾与真实题库混在全局 ACTIVE 池中，
-- 真实模式下被反复复用（题干模板重复）。真实用户不得再收到 fake 生成的题。
-- 既有数据全部默认 PROD：历史 fake 题无法可靠追溯，靠新写入的 generation_env 标记隔离增量。

ALTER TABLE question
    ADD COLUMN generation_env VARCHAR(16) NOT NULL DEFAULT 'PROD';

ALTER TABLE user_mistake
    ADD COLUMN cause VARCHAR(16) NOT NULL DEFAULT 'WRONG_ANSWER';

-- 便于按环境过滤候选题（真实选题查询排除 FAKE）
CREATE INDEX idx_question_status_env_created ON question (status, generation_env, created_at, id);