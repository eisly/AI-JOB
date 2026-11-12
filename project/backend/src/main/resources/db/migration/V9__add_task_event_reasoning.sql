-- 任务事件类型新增 reasoning，用于承载模型思考过程（DeepSeek 思考模式）。

ALTER TABLE `ai_task_event` DROP CHECK `ck_ai_task_event_1`;

ALTER TABLE `ai_task_event`
  ADD CONSTRAINT `ck_ai_task_event_1` CHECK (
    event_type IN ('accepted', 'stage', 'delta', 'reasoning', 'done', 'error', 'cancelled')
  );