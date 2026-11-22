-- V16: 面试总提问数量预算的会话级计数规则
-- 设计基线：.codeartsdoer/specs/voice_interview/design.md 8.3.3
-- 原则：历史会话保持原有"主问题数量上限"语义（MAIN_ONLY）；
--       新建会话由业务代码显式写入 TOTAL_ASKED（主问题 + 追问共用总上限）。
--       不修改已执行的 V15，不回填历史行。

ALTER TABLE `interview_session`
  ADD COLUMN `question_count_rule` VARCHAR(16) NOT NULL DEFAULT 'MAIN_ONLY' AFTER `question_limit`,
  ADD CONSTRAINT `ck_interview_session_rule`
    CHECK (`question_count_rule` IN ('MAIN_ONLY','TOTAL_ASKED'));